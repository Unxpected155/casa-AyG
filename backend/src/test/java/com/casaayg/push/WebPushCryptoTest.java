package com.casaayg.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;

import org.junit.jupiter.api.Test;

/** El cifrado tiene que dar EXACTAMENTE el ejemplo del RFC 8291 (sección 5): si no, Apple y Google no lo abren. */
class WebPushCryptoTest {

    @Test
    void coincideConElEjemploDelRfc8291() throws Exception {
        byte[] plaintext = "When I grow up, I want to be a watermelon".getBytes(StandardCharsets.UTF_8);
        byte[] asPrivate = WebPushCrypto.decode("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw");
        byte[] asPublic = WebPushCrypto.decode(
            "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8");
        byte[] uaPublic = WebPushCrypto.decode(
            "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4");
        byte[] auth = WebPushCrypto.decode("BTBZMqHH6r4Tts7J_aSIgg");
        byte[] salt = WebPushCrypto.decode("DGv6ra1nlYgDCS1FRnbzlw");

        KeyPair asKeys = new KeyPair(WebPushCrypto.publicKey(asPublic), WebPushCrypto.privateKey(asPrivate));
        byte[] body = WebPushCrypto.encrypt(plaintext, uaPublic, auth, asKeys, salt);

        assertThat(WebPushCrypto.encode(body)).isEqualTo(
            "DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK"
            + "6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN");
    }

    @Test
    void lasLlavesPublicasIdaYVuelta() throws Exception {
        KeyPair keys = WebPushCrypto.newKeyPair();
        byte[] raw = WebPushCrypto.uncompressed((java.security.interfaces.ECPublicKey) keys.getPublic());
        assertThat(raw).hasSize(65);
        assertThat(WebPushCrypto.uncompressed(WebPushCrypto.publicKey(raw))).isEqualTo(raw);
    }
}
