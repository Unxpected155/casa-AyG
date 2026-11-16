package com.casaayg.domain;

import java.util.regex.Pattern;

/**
 * Domain primitives de texto. Se recortan los espacios y se rechazan caracteres de control
 * (saltos de línea, NUL, etc.), que no tienen sentido en un nombre y sirven para ataques
 * de inyección en logs o exportaciones.
 */
public final class Text {

    private static final Pattern CONTROL = Pattern.compile("\\p{Cc}");

    private Text() {}

    static String clean(String raw, int max, String what) {
        if (raw == null) {
            throw new IllegalArgumentException(what + " requerido");
        }
        String value = raw.strip();
        if (value.isEmpty() || value.length() > max || CONTROL.matcher(value).find()) {
            throw new IllegalArgumentException(what + " inválido");
        }
        return value;
    }

    /** Descripción de un movimiento (1–200). */
    public record Description(String value) {
        public Description {
            value = clean(value, 200, "descripción");
        }
    }

    /** Nombre de una categoría (1–50). */
    public record CategoryName(String value) {
        public CategoryName {
            value = clean(value, 50, "nombre");
        }
    }

    /** Nombre de un espacio (1–60). */
    public record SpaceName(String value) {
        public SpaceName {
            value = clean(value, 60, "nombre");
        }
    }

    /** Medio de pago opcional (hasta 30). */
    public static String optionalPaymentMethod(String raw) {
        return raw == null || raw.isBlank() ? null : clean(raw, 30, "medio de pago");
    }
}
