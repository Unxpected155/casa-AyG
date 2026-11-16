package com.casaayg.shared.web;

/** 404. También para lo que existe pero es de otro: así no se revela que existe (anti-IDOR). */
public class NotFoundException extends RuntimeException {

    public NotFoundException() {
        super("no encontrado", null, false, false);
    }
}
