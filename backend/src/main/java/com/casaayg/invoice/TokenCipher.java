package com.casaayg.invoice;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cifra el refresh token de Gmail en reposo: AES-256-GCM con la llave {@code gmail_token_key}
 * (secreto del servidor, fuera de la BD y de los respaldos). Formato: IV (12) + cifrado + tag.
 */
@Component
public class TokenCipher {

    private static final byte[] AAD = "casa-ayg:gmail-refresh-token:v1".getBytes(StandardCharsets.US_ASCII);
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    TokenCipher(@Value("${gmail_token_key:}") String hexKey) {
        String k = hexKey.strip();
        this.key = k.length() == 64 ? new SecretKeySpec(HexFormat.of().parseHex(k), "AES") : null;
    }

    public boolean configured() {
        return key != null;
    }

    public byte[] encrypt(String token) {
        require();
        try {
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            c.updateAAD(AAD);
            byte[] sealed = c.doFinal(token.getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes(iv);
            out.writeBytes(sealed);
            return out.toByteArray();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("no se pudo cifrar", e);
        }
    }

    public String decrypt(byte[] data) {
        require();
        if (data == null || data.length < 29 || data.length > 4096) {
            throw new IllegalStateException("no se pudo descifrar");
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, data, 0, 12));
            c.updateAAD(AAD);
            return new String(c.doFinal(data, 12, data.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("no se pudo descifrar");
        }
    }

    private void require() {
        if (key == null) {
            throw new IllegalStateException("falta gmail_token_key");
        }
    }
}
