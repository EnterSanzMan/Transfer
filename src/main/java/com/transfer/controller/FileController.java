package com.transfer.controller;

import com.transfer.model.StoredFile;
import com.transfer.service.FileStorageService;
import com.transfer.service.StorageLimitException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Controller
public class FileController {

    private static final Logger log = LoggerFactory.getLogger(FileController.class);

    private final FileStorageService fileStorageService;

    public FileController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    /**
     * Pantalla principal con pestañas 'descargas' y 'subir'.
     * Al entrar en la web, se purgan automáticamente los ficheros con más de 30 días.
     */
    @GetMapping("/")
    public String index(
            @RequestParam(value = "tab", defaultValue = "descargas") String tab,
            Model model) {

        // Requisito: Un fichero que lleva más de 30 días a la hora de entrar en la web se borra del disco y de la BBDD
        int purgedCount = fileStorageService.purgeExpiredFiles();
        if (purgedCount > 0) {
            model.addAttribute("purgeNotice", "Se han purgado automáticamente " + purgedCount + " fichero(s) de más de 30 días.");
        }

        List<StoredFile> files = fileStorageService.getAllFiles();
        model.addAttribute("files", files);
        model.addAttribute("activeTab", tab);
        model.addAttribute("totalFiles", files.size());

        return "index";
    }

    /**
     * Subida de fichero desde la pestaña 'subir fichero'.
     */
    @PostMapping("/files/upload")
    public String handleFileUpload(
            @RequestParam("file") MultipartFile file,
            RedirectAttributes redirectAttributes) {

        if (file.isEmpty()) {
            redirectAttributes.addFlashAttribute("errorMessage", "Por favor, selecciona un fichero para subir.");
            return "redirect:/?tab=subir";
        }

        try {
            StoredFile stored = fileStorageService.store(file);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Fichero '" + stored.getOriginalFilename() + "' (" + stored.getFormattedSize() + ") subido con éxito.");
        } catch (StorageLimitException | IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
            return "redirect:/?tab=subir";
        } catch (Exception e) {
            // El detalle va al log; al usuario no se le muestran rutas ni trazas internas
            log.error("Error al subir fichero", e);
            redirectAttributes.addFlashAttribute("errorMessage", "No se pudo guardar el fichero. Inténtalo de nuevo.");
            return "redirect:/?tab=subir";
        }

        return "redirect:/?tab=descargas";
    }

    /**
     * Descargar fichero (mantiene el fichero en el sistema).
     */
    @GetMapping("/files/{id}/download")
    public ResponseEntity<Resource> downloadFile(@PathVariable Long id) {
        StoredFile storedFile = fileStorageService.getFileById(id);
        if (storedFile == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Fichero no encontrado.");
        }

        Resource resource = fileStorageService.loadAsResource(storedFile);
        String headerValue = buildContentDispositionHeader(storedFile.getOriginalFilename());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, headerValue)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(storedFile.getSize())
                .body(resource);
    }

    /**
     * Descargar y borrar: transmite el fichero al cliente y, si la transferencia
     * termina bien, lo elimina de disco y BBDD. Es POST (con token CSRF) para que
     * un enlace o una imagen de otra web no pueda borrar ficheros.
     */
    @PostMapping("/files/{id}/download-and-delete")
    public ResponseEntity<StreamingResponseBody> downloadAndDeleteFile(@PathVariable Long id) {
        StoredFile storedFile = fileStorageService.getFileById(id);
        if (storedFile == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Fichero no encontrado.");
        }

        Path path = fileStorageService.loadFilePath(storedFile);
        if (!Files.exists(path)) {
            fileStorageService.deleteFileById(id);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "El fichero físico ya no existe en disco.");
        }

        long fileLength;
        try {
            fileLength = Files.size(path);
        } catch (IOException e) {
            fileLength = storedFile.getSize();
        }

        String headerValue = buildContentDispositionHeader(storedFile.getOriginalFilename());

        StreamingResponseBody stream = outputStream -> {
            try (InputStream inputStream = Files.newInputStream(path)) {
                inputStream.transferTo(outputStream);
                outputStream.flush();
            }
            // Solo se llega aquí si se ha enviado entero: si la descarga se corta,
            // transferTo/flush lanzan excepción y el fichero se conserva
            fileStorageService.deleteFileById(id);
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, headerValue)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(fileLength)
                .body(stream);
    }

    /**
     * Borrado directo sin descargar.
     */
    @PostMapping("/files/{id}/delete")
    public String deleteFile(@PathVariable Long id, RedirectAttributes redirectAttributes) {
        StoredFile file = fileStorageService.getFileById(id);
        String filename = (file != null) ? file.getOriginalFilename() : "Fichero";

        boolean deleted = fileStorageService.deleteFileById(id);
        if (deleted) {
            redirectAttributes.addFlashAttribute("successMessage", "Fichero '" + filename + "' eliminado correctamente.");
        } else {
            redirectAttributes.addFlashAttribute("errorMessage", "No se encontró el fichero a eliminar.");
        }

        return "redirect:/?tab=descargas";
    }

    private String buildContentDispositionHeader(String filename) {
        String safeAscii = filename.replaceAll("[^a-zA-Z0-9._-]", "_");
        String encodedFilename = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + safeAscii + "\"; filename*=UTF-8''" + encodedFilename;
    }
}
