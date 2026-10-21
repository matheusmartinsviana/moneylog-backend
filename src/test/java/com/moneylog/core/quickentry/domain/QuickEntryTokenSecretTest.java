package com.moneylog.core.quickentry.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Segredo do token de lançamento rápido: formato, hash e unicidade")
class QuickEntryTokenSecretTest {

    @Test
    @DisplayName("tem o formato mlq_<8>_<43 base64url> e o prefixo é o trecho de 8 caracteres")
    void deveTerFormato() {
        QuickEntryTokenSecret secret = QuickEntryTokenSecret.generate();

        assertThat(secret.value()).matches("^mlq_[A-Za-z0-9]{8}_[A-Za-z0-9_-]{43}$");
        assertThat(QuickEntryTokenSecret.hasValidFormat(secret.value())).isTrue();
        assertThat(secret.value()).startsWith("mlq_" + secret.prefix() + "_");
    }

    @Test
    @DisplayName("o hash é o SHA-256 em hexadecimal do token inteiro e não contém o segredo")
    void deveGerarHash() {
        QuickEntryTokenSecret secret = QuickEntryTokenSecret.generate();

        assertThat(secret.hash()).hasSize(QuickEntryToken.HASH_LENGTH).matches("[0-9a-f]{64}").isEqualTo(QuickEntryTokenSecret.hashOf(secret.value()));
        assertThat(secret.hash()).doesNotContain(secret.prefix());
        assertThat(QuickEntryTokenSecret.hashOf("mlq_abcdefgh_" + "A".repeat(43)))
                .isEqualTo(QuickEntryTokenSecret.hashOf("mlq_abcdefgh_" + "A".repeat(43)))
                .isNotEqualTo(QuickEntryTokenSecret.hashOf("mlq_abcdefgh_" + "B".repeat(43)));
    }

    @Test
    @DisplayName("toString não vaza o segredo")
    void naoDeveVazarNoToString() {
        QuickEntryTokenSecret secret = QuickEntryTokenSecret.generate();

        assertThat(secret.toString()).doesNotContain(secret.value());
    }

    @Test
    @DisplayName("mil tokens não se repetem")
    void naoDeveRepetir() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            seen.add(QuickEntryTokenSecret.generate().value());
        }
        assertThat(seen).hasSize(1000);
    }

    @Test
    @DisplayName("formato inválido é recusado")
    void deveRecusarFormatoInvalido() {
        assertThat(QuickEntryTokenSecret.hasValidFormat(null)).isFalse();
        assertThat(QuickEntryTokenSecret.hasValidFormat("")).isFalse();
        assertThat(QuickEntryTokenSecret.hasValidFormat("eyJhbGciOi.JWT.token")).isFalse();
        assertThat(QuickEntryTokenSecret.hasValidFormat("mlq_curto_" + "A".repeat(43))).isFalse();
        assertThat(QuickEntryTokenSecret.hasValidFormat("mlq_abcdefgh_" + "A".repeat(42))).isFalse();
    }
}
