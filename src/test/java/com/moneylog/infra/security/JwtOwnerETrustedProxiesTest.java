package com.moneylog.infra.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.Jwt;

import com.moneylog.core.auth.app.gateways.UserGateway;

@DisplayName("Validador do dono do JWT (B6) e guarda de proxies (B11)")
class JwtOwnerETrustedProxiesTest {

    private static Jwt jwt(String subject) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "HS256").issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (subject != null) {
            builder.subject(subject);
        } else {
            builder.claim("x", "y");
        }
        return builder.build();
    }

    @Test
    @DisplayName("dono existente passa; dono removido, subject ausente ou que não é UUID falham (401, não 500 de FK)")
    void deveValidarODono() {
        UserGateway users = mock(UserGateway.class);
        UUID known = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        when(users.existsById(known)).thenReturn(true);
        when(users.existsById(gone)).thenReturn(false);
        JwtOwnerValidator validator = new JwtOwnerValidator(users);

        assertThat(validator.validate(jwt(known.toString())).hasErrors()).isFalse();
        assertThat(validator.validate(jwt(gone.toString())).hasErrors()).isTrue();
        assertThat(validator.validate(jwt(null)).hasErrors()).isTrue();
        assertThat(validator.validate(jwt("nao-e-uuid")).hasErrors()).isTrue();
    }

    @Test
    @DisplayName("trusted-proxies vazio: com 'prod' não sobe; com lista preenchida, dev ou it passa")
    void deveFalharEmProducaoSemProxies() {
        assertThatThrownBy(() -> TrustedProxiesGuard.validate(List.of(), prod()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MONEYLOG_TRUSTED_PROXIES");
        assertThatCode(() -> TrustedProxiesGuard.validate(List.of("10.0.0.0/8"), prod())).doesNotThrowAnyException();
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertThatCode(() -> TrustedProxiesGuard.validate(List.of(), dev)).doesNotThrowAnyException();
        MockEnvironment it = new MockEnvironment();
        it.setActiveProfiles("it");
        assertThatCode(() -> TrustedProxiesGuard.validate(List.of(), it)).doesNotThrowAnyException();
        assertThatCode(() -> TrustedProxiesGuard.validate(List.of(), new MockEnvironment())).doesNotThrowAnyException();
    }

    private static MockEnvironment prod() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod");
        return env;
    }
}
