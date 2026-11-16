package com.casaayg.shared.web;

/** 409 con un mensaje escrito por la app (seguro de mostrar al usuario). */
public class ConflictException extends RuntimeException {

    public ConflictException(String safeMessage) {
        super(safeMessage, null, false, false);
    }
}
