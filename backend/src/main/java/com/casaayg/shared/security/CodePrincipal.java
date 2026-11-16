package com.casaayg.shared.security;

import java.io.Serializable;
import java.util.UUID;

/** Usuario que entró con un código de inscripción o de recuperación. */
public record CodePrincipal(UUID id, String username, String purpose) implements Serializable {

    public static final String ENROLLMENT = "INSCRIPCION";
    public static final String RECOVERY = "RECUPERACION";
}
