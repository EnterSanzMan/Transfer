package com.transfer.controller;

import com.transfer.model.StoredFile;
import com.transfer.service.FileStorageService;
import com.transfer.service.StorageLimitException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

/**
 * API para clientes automáticos. El token solo permite subir ficheros:
 * listar, descargar y borrar siguen requiriendo sesión en la web.
 */
@RestController
public class ApiFileController {

    private static final Logger log = LoggerFactory.getLogger(ApiFileController.class);

    private final FileStorageService fileStorageService;

    public ApiFileController(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    @PostMapping("/api/files")
    public ResponseEntity<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        try {
            StoredFile stored = fileStorageService.store(file);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of(
                    "id", stored.getId(),
                    "name", stored.getOriginalFilename(),
                    "size", stored.getSize(),
                    "formattedSize", stored.getFormattedSize()));
        } catch (StorageLimitException e) {
            return error(HttpStatus.INSUFFICIENT_STORAGE, e.getMessage());
        } catch (IllegalArgumentException e) {
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IOException | RuntimeException e) {
            log.error("Error al subir fichero por la API", e);
            return error(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo guardar el fichero.");
        }
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
