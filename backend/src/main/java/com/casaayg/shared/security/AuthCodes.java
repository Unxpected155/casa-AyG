package com.casaayg.shared.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Códigos de inscripción y recuperación: 24 caracteres hexadecimales (96 bits aleatorios),
 * mostrados en grupos de 4 (ABCD-EF01-...). En la BD solo se guarda su SHA-256.
 * El mismo formato y hash los usa admin_create_user en V4.
 */
public final class AuthCodes {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern VALID = Pattern.compile("[0-9A-F]{24}");

    private AuthCodes() {}

    public static String generate() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().withUpperCase().formatHex(bytes);
    }

    public static String format(String code) {
        return code.replaceAll("(.{4})(?!$)", "$1-");
    }

    /** Acepta guiones, espacios y minúsculas. Vacío si no tiene la forma de un código. */
    public static Optional<String> normalize(String input) {
        if (input == null || input.length() > 64) {
            return Optional.empty();
        }
        String code = input.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        return VALID.matcher(code).matches() ? Optional.of(code) : Optional.empty();
    }

    public static byte[] hash(String normalizedCode) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(normalizedCode.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
