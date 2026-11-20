package com.casaayg.invoice;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.casaayg.shared.web.ConflictException;

/**
 * La cédula de cada persona, para saber de quién es una factura (ADR-006). Solo se guarda
 * HMAC-SHA256(llave, cédula); la llave es el secreto {@code tax_id_key} (fuera de la BD).
 * La cédula en texto nunca se guarda, ni se registra, ni vuelve a la pantalla.
 */
@Service
public class TaxIdService {

    private final JdbcClient jdbc;
    private final SecretKeySpec key;

    TaxIdService(JdbcClient jdbc, @Value("${tax_id_key:}") String hexKey) {
        this.jdbc = jdbc;
        String k = hexKey.strip();
        this.key = k.length() >= 64 ? new SecretKeySpec(HexFormat.of().parseHex(k), "HmacSHA256") : null;
    }

    public boolean configured() {
        return key != null;
    }

    /**
     * Solo los dígitos. Física 9 (1-0123-0456), jurídica o NITE 10, DIMEX 11 o 12. Hacienda
     * escribe la física sin el 0 inicial: "01-0123-0456" queda igual que "1-0123-0456".
     */
    public static Optional<String> normalize(String raw) {
        if (raw == null || raw.length() > 30 || !raw.matches("[0-9 -]+")) {
            return Optional.empty();
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() == 10 && digits.charAt(0) == '0') {
            digits = digits.substring(1);
        }
        if (digits.length() < 9 || digits.length() > 12 || digits.charAt(0) == '0') {
            return Optional.empty();
        }
        return Optional.of(digits);
    }

    byte[] hmac(String digits) {
        if (key == null) {
            throw new ConflictException("Las facturas todavía no están configuradas en el servidor.");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(("CR:" + digits).getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Dentro de una transacción con sesión (la de la persona). */
    void setMine(String raw) {
        String digits = normalize(raw).orElseThrow(IllegalArgumentException::new);
        Boolean ok = jdbc.sql("SELECT tax_id_set(?)").param(hmac(digits)).query(Boolean.class).single();
        if (!Boolean.TRUE.equals(ok)) {
            throw new ConflictException("Esa cédula ya la tiene otra persona en Casa A&G.");
        }
    }

    void clearMine() {
        jdbc.sql("SELECT tax_id_clear()").query().listOfRows();
    }

    boolean mineIsSet() {
        return Boolean.TRUE.equals(jdbc.sql("SELECT tax_id_is_set()").query(Boolean.class).single());
    }

    /** De quién es la cédula del receptor de una factura; vacío si no es de nadie aquí. */
    public Optional<UUID> ownerOf(String receptorId) {
        if (key == null) {
            return Optional.empty();
        }
        return normalize(receptorId).flatMap(d -> jdbc.sql("SELECT tax_id_owner(?)").param(hmac(d))
            .query(UUID.class).optional());
    }
}
