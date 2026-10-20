package com.moneylog.core.sk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.sk.id.SecureToken;

@DisplayName("SecureToken")
class SecureTokenTest {

    @Test
    @DisplayName("gera 256 bits em base64url, sem repetir")
    void generatesUrlSafeUniqueTokens() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String token = SecureToken.generate();
            assertThat(token).matches("[A-Za-z0-9_-]{43}");
            seen.add(token);
        }
        assertThat(seen).hasSize(200);
    }

    @Test
    @DisplayName("o hash é SHA-256 em hexadecimal, determinístico e diferente do token")
    void hashesWithSha256() {
        assertThat(SecureToken.hash("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(SecureToken.hash("abc")).isEqualTo(SecureToken.hash("abc")).isNotEqualTo(SecureToken.hash("abd"));
    }
}
