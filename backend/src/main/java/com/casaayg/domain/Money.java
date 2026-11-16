package com.casaayg.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Domain primitive: un monto que ya fue validado. Si existe un Money, es correcto:
 * no negativo, a lo sumo 2 decimales y dentro de numeric(14,2).
 */
public record Money(BigDecimal amount, Currency currency) {

    public static final BigDecimal MAX = new BigDecimal("999999999999.99");

    public Money {
        Objects.requireNonNull(amount, "monto");
        Objects.requireNonNull(currency, "moneda");
        if (amount.signum() < 0 || amount.compareTo(MAX) > 0) {
            throw new IllegalArgumentException("monto fuera de rango");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("máximo 2 decimales");
        }
        amount = amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    public enum Currency { CRC, USD }
}
