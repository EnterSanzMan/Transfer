package com.transfer.service;

import com.transfer.model.StoredFile;
import com.transfer.repository.StoredFileRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);

    private final Path rootLocation;
    private final StoredFileRepository fileRepository;
    private final int retentionDays;
    private final long maxStorageBytes;
    private final long minFreeDiskBytes;
    /** Bytes de subidas que se están copiando ahora mismo y aún no constan en la BBDD. */
    private final AtomicLong reservedBytes = new AtomicLong();

    public FileStorageService(
            @Value("${transfer.upload-dir:./uploads}") String uploadDir,
            @Value("${transfer.retention-days:30}") int retentionDays,
            @Value("${transfer.max-storage-mb:0}") long maxStorageMb,
            @Value("${transfer.min-free-disk-mb:0}") long minFreeDiskMb,
            StoredFileRepository fileRepository) {
        this.rootLocation = Paths.get(uploadDir).toAbsolutePath().normalize();
        this.retentionDays = retentionDays;
        this.maxStorageBytes = Math.max(0, maxStorageMb) * 1024 * 1024;
        this.minFreeDiskBytes = Math.max(0, minFreeDiskMb) * 1024 * 1024;
        this.fileRepository = fileRepository;
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(rootLocation);
            log.info("Directorio de subida configurado en: {}", rootLocation);
        } catch (IOException e) {
            throw new RuntimeException("No se pudo inicializar la carpeta de almacenamiento de ficheros: " + rootLocation, e);
        }
    }

    public Path getRootLocation() {
        return rootLocation;
    }

    @Transactional
    public StoredFile store(MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("No se puede subir un fichero vacío");
        }

        String rawFilename = StringUtils.cleanPath(Objects.requireNonNull(file.getOriginalFilename()));
        if (rawFilename.contains("..")) {
            throw new IllegalArgumentException("Nombre de fichero no válido: " + rawFilename);
        }

        String uniqueFilename = UUID.randomUUID().toString() + "_" + rawFilename.replaceAll("[^a-zA-Z0-9._-]", "_");
        Path destination = this.rootLocation.resolve(uniqueFilename).normalize();

        long sizeInBytes = file.getSize();
        reserveSpace(sizeInBytes);
        try {
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            StoredFile storedFile = new StoredFile(
                    rawFilename,
                    uniqueFilename,
                    sizeInBytes,
                    formatFileSize(sizeInBytes),
                    LocalDateTime.now()
            );
            return fileRepository.saveAndFlush(storedFile);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(destination);
            throw e;
        } finally {
            reservedBytes.addAndGet(-sizeInBytes);
        }
    }

    /**
     * Comprueba que la subida cabe en la cuota y en el disco, y la reserva
     * para que dos subidas simultáneas no se salten el límite.
     */
    private synchronized void reserveSpace(long size) throws IOException {
        long inFlight = reservedBytes.get();
        if (maxStorageBytes > 0 && fileRepository.totalSize() + inFlight + size > maxStorageBytes) {
            throw new StorageLimitException("No queda espacio: el almacenamiento está lleno (máximo "
                    + formatFileSize(maxStorageBytes) + "). Borra algún fichero e inténtalo de nuevo.");
        }
        if (minFreeDiskBytes > 0 && Files.getFileStore(rootLocation).getUsableSpace() - inFlight - size < minFreeDiskBytes) {
            throw new StorageLimitException("No queda espacio libre suficiente en el disco del servidor.");
        }
        reservedBytes.addAndGet(size);
    }

    public List<StoredFile> getAllFiles() {
        return fileRepository.findAllByOrderByUploadDateDesc();
    }

    public StoredFile getFileById(Long id) {
        return fileRepository.findById(id).orElse(null);
    }

    public Path loadFilePath(StoredFile storedFile) {
        return rootLocation.resolve(storedFile.getStoredFilename()).normalize();
    }

    public Resource loadAsResource(StoredFile storedFile) {
        try {
            Path file = loadFilePath(storedFile);
            Resource resource = new UrlResource(file.toUri());
            if (resource.exists() && resource.isReadable()) {
                return resource;
            } else {
                throw new RuntimeException("No se puede leer el fichero en disco: " + storedFile.getOriginalFilename());
            }
        } catch (MalformedURLException e) {
            throw new RuntimeException("Error en la ruta del fichero: " + storedFile.getOriginalFilename(), e);
        }
    }

    @Transactional
    public boolean deleteFileById(Long id) {
        StoredFile storedFile = fileRepository.findById(id).orElse(null);
        if (storedFile == null) {
            return false;
        }

        deletePhysicalFile(storedFile.getStoredFilename());
        fileRepository.delete(storedFile);
        log.info("Fichero eliminado de disco y BBDD: {}", storedFile.getOriginalFilename());
        return true;
    }

    /**
     * Purga ficheros que llevan más de 30 días subidos.
     * Se invoca automáticamente al entrar en la web y también por tarea programada.
     */
    @Transactional
    public int purgeExpiredFiles() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        List<StoredFile> expiredFiles = fileRepository.findByUploadDateBefore(cutoff);

        if (expiredFiles.isEmpty()) {
            return 0;
        }

        int count = 0;
        for (StoredFile file : expiredFiles) {
            deletePhysicalFile(file.getStoredFilename());
            fileRepository.delete(file);
            count++;
        }

        log.info("Purga de ficheros expirados (> {} días): {} ficheros eliminados.", retentionDays, count);
        return count;
    }

    @Scheduled(cron = "0 0 3 * * *") // A las 3:00 AM cada día
    public void scheduledPurge() {
        purgeExpiredFiles();
    }

    private void deletePhysicalFile(String storedFilename) {
        try {
            Path path = rootLocation.resolve(storedFilename).normalize();
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.error("No se pudo eliminar el fichero físico: {}", storedFilename, e);
        }
    }

    public static String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }
}
