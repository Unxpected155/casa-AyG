package com.casaayg.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class TokenCipherTest {

    static final String KEY = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

    @Test
    void cifraYDescifraSinDejarElTokenVisible() {
        TokenCipher c = new TokenCipher(KEY);
        byte[] a = c.encrypt("1//refresh-token-de-prueba");
        byte[] b = c.encrypt("1//refresh-token-de-prueba");
        assertThat(new String(a, StandardCharsets.ISO_8859_1)).doesNotContain("refresh-token");
        assertThat(a).isNotEqualTo(b); // IV distinto cada vez
        assertThat(c.decrypt(a)).isEqualTo("1//refresh-token-de-prueba");
    }

    @Test
    void unCambioEnElCifradoOOtraLlaveNoDescifran() {
        TokenCipher c = new TokenCipher(KEY);
        byte[] a = c.encrypt("token");
        a[a.length - 1] ^= 1;
        assertThatThrownBy(() -> c.decrypt(a)).isInstanceOf(IllegalStateException.class);
        TokenCipher other = new TokenCipher(KEY.replace('0', '1'));
        assertThatThrownBy(() -> other.decrypt(c.encrypt("token"))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sinLlaveNoEstaConfigurado() {
        assertThat(new TokenCipher("").configured()).isFalse();
        assertThatThrownBy(() -> new TokenCipher("").encrypt("x")).isInstanceOf(IllegalStateException.class);
    }
}
