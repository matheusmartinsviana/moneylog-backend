package com.moneylog.core.quickentry.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Chave de idempotência opaca do lançamento rápido (ADR-034, seção 5)")
class QuickIdempotencyKeyTest {

    private static final String A64 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String A65 = A64 + "a";
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_USER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    @DisplayName("UUID vale como está")
    void deveUsarOUuidComoEsta() {
        UUID key = UUID.randomUUID();
        assertThat(QuickIdempotencyKey.resolve(USER, key.toString())).isEqualTo(key);
        assertThat(QuickIdempotencyKey.resolve(OTHER_USER, key.toString().toUpperCase())).isEqualTo(key);
    }

    @Test
    @DisplayName("string opaca vira o UUID determinístico de userId:chave; outro usuário ou outra chave dão outro UUID")
    void deveDerivarDoUsuarioEDaChave() {
        UUID first = QuickIdempotencyKey.resolve(USER, "20261021153000");
        assertThat(QuickIdempotencyKey.resolve(USER, "20261021153000")).isEqualTo(first);
        assertThat(QuickIdempotencyKey.resolve(OTHER_USER, "20261021153000")).isNotEqualTo(first);
        assertThat(QuickIdempotencyKey.resolve(USER, "20261021153001")).isNotEqualTo(first);
        assertThat(first).isEqualTo(UUID.nameUUIDFromBytes((USER + ":20261021153000").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345678", "abc_DEF-123", A64})
    void deveAceitarOFormatoOpaco(String key) {
        assertThat(QuickIdempotencyKey.isValid(key)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234567", A65, "com espaço 123", "acentuação12", "a/b/c/d/e/f", "", "chave;com;ponto"})
    void deveRecusarForaDoFormato(String key) {
        assertThat(QuickIdempotencyKey.isValid(key)).isFalse();
        assertThatThrownBy(() -> QuickIdempotencyKey.resolve(USER, key)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("nulo é inválido")
    void deveRecusarNulo() {
        assertThat(QuickIdempotencyKey.isValid(null)).isFalse();
    }
}
