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
    @ValueSource(strings = {"1234567", A65, "a/b/c/d/e/f", "", "/// 12345 ///", "12/34/56 7"})
    void deveRecusarForaDoFormato(String key) {
        assertThat(QuickIdempotencyKey.isValid(key)).isFalse();
        assertThatThrownBy(() -> QuickIdempotencyKey.resolve(USER, key)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("normaliza data formatada, ISO, controles, NBSP e marcas de direção")
    void deveNormalizar() {
        assertThat(QuickIdempotencyKey.normalize("21/10/2026 17:11")).isEqualTo("211020261711");
        assertThat(QuickIdempotencyKey.normalize("2026-10-21T17:11:00")).isEqualTo("2026-10-21T171100");
        assertThat(QuickIdempotencyKey.normalize("\u200E21/10/2026\u00A017:11\u200F\r\n\t")).isEqualTo("211020261711");
        assertThat(QuickIdempotencyKey.normalize("\u0000ab\u0001cdef\u007Fgh")).isEqualTo("abcdefgh");
        assertThat(QuickIdempotencyKey.normalize(" " + A64.substring(0, 10) + " ")).isEqualTo(A64.substring(0, 10));
        UUID id = UUID.randomUUID();
        assertThat(QuickIdempotencyKey.normalize(" " + id + "\u00A0")).isEqualTo(id.toString());
        assertThat(QuickIdempotencyKey.resolve(USER, "21/10/2026 17:11")).isEqualTo(QuickIdempotencyKey.resolve(USER, "211020261711"));
    }

    @Test
    @DisplayName("valor bruto acima de 200 caracteres não é normalizado")
    void deveRecusarBrutoLongo() {
        assertThat(QuickIdempotencyKey.normalize("a".repeat(200))).isNull();
        assertThat(QuickIdempotencyKey.normalize("a".repeat(30) + " ".repeat(170))).isEqualTo("a".repeat(30));
        assertThat(QuickIdempotencyKey.normalize("a".repeat(200) + " ")).isNull();
        assertThat(QuickIdempotencyKey.explain("a".repeat(201))).contains("201").doesNotContain("aaaa");
    }

    @Test
    @DisplayName("a mensagem conta caracteres recebidos, descartados e restantes, sem ecoar o valor")
    void deveExplicarSemEcoar() {
        String message = QuickIdempotencyKey.explain("12/34 56");

        assertThat(message).isEqualTo("Formato inválido: chegaram 8 caracteres, 2 inválidos descartados; sobraram 6 e o mínimo é 8.");
        assertThat(message).doesNotContain("12/34").doesNotContain("123456");
        assertThat(QuickIdempotencyKey.explain(A65)).contains("sobraram 65 e o máximo é 64");
    }

    @Test
    @DisplayName("nulo é inválido")
    void deveRecusarNulo() {
        assertThat(QuickIdempotencyKey.isValid(null)).isFalse();
    }
}
