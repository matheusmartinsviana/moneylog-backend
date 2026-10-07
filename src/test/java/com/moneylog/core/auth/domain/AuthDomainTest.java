package com.moneylog.core.auth.domain;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.auth.app.RefreshTokenHash;
import com.moneylog.core.auth.exception.AuthExceptions.InvalidCredentialsException;
import com.moneylog.core.auth.exception.AuthExceptions.RefreshInvalidException;

@DisplayName("Domínio de auth")
class AuthDomainTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    @Test
    @DisplayName("e-mail é normalizado (sem espaços, minúsculas) e não pode ser vazio")
    void deveNormalizarEmail() {
        assertThat(new User(USER_ID, "  Dono@MoneyLog.Test ").email()).isEqualTo("dono@moneylog.test");
        assertThatThrownBy(() -> new User(USER_ID, " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new User(USER_ID, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("refresh token novo não está revogado, substituído nem expirado")
    void deveEmitirTokenValido() {
        RefreshToken token = RefreshToken.issue(UUID.randomUUID(), USER_ID, UUID.randomUUID(), "h".repeat(64), NOW, NOW.plusSeconds(60));

        assertThat(token.isRevoked()).isFalse();
        assertThat(token.isReplaced()).isFalse();
        assertThat(token.isExpired(NOW)).isFalse();
        assertThat(token.isExpired(NOW.plusSeconds(60))).isTrue();
    }

    @Test
    @DisplayName("hash do refresh é SHA-256 em hexadecimal, determinístico")
    void deveCalcularHash() {
        assertThat(RefreshTokenHash.of("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    @DisplayName("as exceções de auth têm code estável")
    void deveTerCodesEstaveis() {
        assertThat(new InvalidCredentialsException().code()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(new RefreshInvalidException().code()).isEqualTo("REFRESH_INVALID");
    }
}
