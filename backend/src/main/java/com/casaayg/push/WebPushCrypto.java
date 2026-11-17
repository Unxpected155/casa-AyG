package com.casaayg.push;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cifrado de mensajes Web Push (RFC 8291, "aes128gcm" de RFC 8188) solo con la JDK.
 * El mensaje se cifra para UN dispositivo (su llave p256dh y su secreto auth): el servicio de
 * push de Apple/Google lo transporta pero no puede leerlo.
 */
final class WebPushCrypto {

    static final int RECORD_SIZE = 4096;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Decoder B64 = Base64.getUrlDecoder();
    private static final Base64.Encoder B64_OUT = Base64.getUrlEncoder().withoutPadding();

    private WebPushCrypto() {}

    static ECParameterSpec p256() {
        try {
            AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
            params.init(new ECGenParameterSpec("secp256r1"));
            return params.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static KeyPair newKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
            return gen.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] decode(String base64url) {
        return B64.decode(base64url.strip().replace("=", ""));
    }

    static String encode(byte[] bytes) {
        return B64_OUT.encodeToString(bytes);
    }

    /** Punto sin comprimir (0x04 || X || Y), 65 bytes: así viaja una llave pública P-256. */
    static byte[] uncompressed(ECPublicKey key) {
        byte[] out = new byte[65];
        out[0] = 0x04;
        copyFixed(key.getW().getAffineX(), out, 1);
        copyFixed(key.getW().getAffineY(), out, 33);
        return out;
    }

    static ECPublicKey publicKey(byte[] uncompressed) throws GeneralSecurityException {
        if (uncompressed.length != 65 || uncompressed[0] != 0x04) {
            throw new GeneralSecurityException("llave pública inválida");
        }
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(uncompressed, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(uncompressed, 33, 65));
        ECParameterSpec spec = p256();
        // KeyFactory NO comprueba que el punto esté en la curva: se verifica aquí
        // (y² = x³ + ax + b mod p), para no aceptar llaves basura ni puntos de otra curva.
        BigInteger p = ((java.security.spec.ECFieldFp) spec.getCurve().getField()).getP();
        if (x.compareTo(p) >= 0 || y.compareTo(p) >= 0
                || !y.modPow(BigInteger.TWO, p).equals(
                    x.modPow(BigInteger.valueOf(3), p).add(spec.getCurve().getA().multiply(x)).add(spec.getCurve().getB()).mod(p))) {
            throw new GeneralSecurityException("el punto no está en la curva P-256");
        }
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), spec));
    }

    static ECPrivateKey privateKey(byte[] scalar) throws GeneralSecurityException {
        return (ECPrivateKey) KeyFactory.getInstance("EC")
            .generatePrivate(new ECPrivateKeySpec(new BigInteger(1, scalar), p256()));
    }

    /** Cifra con llaves efímeras y sal aleatoria (lo normal). */
    static byte[] encrypt(byte[] payload, byte[] uaPublic, byte[] authSecret) throws GeneralSecurityException {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return encrypt(payload, uaPublic, authSecret, newKeyPair(), salt);
    }

    /** RFC 8291, sección 3.4. Los parámetros fijos existen para probar contra el ejemplo del RFC. */
    static byte[] encrypt(byte[] payload, byte[] uaPublic, byte[] authSecret, KeyPair asKeys, byte[] salt)
            throws GeneralSecurityException {
        if (authSecret.length != 16) {
            throw new GeneralSecurityException("secreto auth inválido");
        }
        ECPublicKey ua = publicKey(uaPublic);
        byte[] asPublic = uncompressed((ECPublicKey) asKeys.getPublic());

        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(asKeys.getPrivate());
        agreement.doPhase(ua, true);
        byte[] ecdhSecret = agreement.generateSecret();

        byte[] keyInfo = concat("WebPush: info\0".getBytes(StandardCharsets.US_ASCII), uaPublic, asPublic);
        byte[] ikm = hkdf(authSecret, ecdhSecret, keyInfo, 32);
        byte[] prk = hmac(salt, ikm);
        byte[] cek = expand(prk, "Content-Encoding: aes128gcm\0".getBytes(StandardCharsets.US_ASCII), 16);
        byte[] nonce = expand(prk, "Content-Encoding: nonce\0".getBytes(StandardCharsets.US_ASCII), 12);

        // Un solo registro: el mensaje + el delimitador 0x02 de "último registro".
        if (payload.length + 1 + 16 > RECORD_SIZE) {
            throw new GeneralSecurityException("mensaje demasiado largo");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(cek, "AES"), new GCMParameterSpec(128, nonce));
        byte[] ciphertext = cipher.doFinal(concat(payload, new byte[] {0x02}));

        ByteBuffer header = ByteBuffer.allocate(16 + 4 + 1 + asPublic.length);
        header.put(salt).putInt(RECORD_SIZE).put((byte) asPublic.length).put(asPublic);
        return concat(header.array(), ciphertext);
    }

    static byte[] hkdf(byte[] salt, byte[] ikm, byte[] info, int length) throws GeneralSecurityException {
        return expand(hmac(salt, ikm), info, length);
    }

    /** HKDF-Expand de un bloque (alcanza para 32 bytes con SHA-256). */
    static byte[] expand(byte[] prk, byte[] info, int length) throws GeneralSecurityException {
        return Arrays.copyOf(hmac(prk, concat(info, new byte[] {0x01})), length);
    }

    static byte[] hmac(byte[] key, byte[] data) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] p : parts) {
            length += p.length;
        }
        byte[] out = new byte[length];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }

    private static void copyFixed(BigInteger value, byte[] out, int offset) {
        byte[] raw = value.toByteArray();
        int start = Math.max(0, raw.length - 32);
        int len = raw.length - start;
        System.arraycopy(raw, start, out, offset + 32 - len, len);
    }
}
