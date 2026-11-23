package com.casaayg.invoice;

/** Para que otras pruebas guarden un token cifrado como lo haría la app. */
public final class TokenCipherTestAccess {

    private TokenCipherTestAccess() {}

    public static byte[] encrypt(String hexKey, String token) {
        return new TokenCipher(hexKey).encrypt(token);
    }
}
