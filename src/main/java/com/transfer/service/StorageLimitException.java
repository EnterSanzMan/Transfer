package com.transfer.service;

/**
 * La subida no cabe: supera la cuota total configurada o dejaría el disco sin
 * el espacio libre mínimo. El mensaje se puede mostrar al usuario.
 */
public class StorageLimitException extends RuntimeException {

    public StorageLimitException(String message) {
        super(message);
    }
}
