package com.moneylog.infra.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.domain.User;
import com.moneylog.core.auth.domain.UserRole;
import com.moneylog.core.auth.domain.UserStatus;

@DisplayName("Conversor do JWT com papel do banco (B6) e guarda de proxies (B11)")
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
    @DisplayName("usuário ativo passa com o papel do banco; removido, desativado, subject ausente ou que não é UUID falham (401, não 500 de FK)")
    void deveValidarOUsuarioEOPapel() {
        UserGateway users = mock(UserGateway.class);
        UUID owner = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID disabled = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        when(users.findById(owner)).thenReturn(Optional.of(User.owner(owner, "dono@example.com")));
        when(users.findById(member)).thenReturn(Optional.of(User.member(member, "membro@example.com")));
        when(users.findById(disabled)).thenReturn(Optional.of(new User(disabled, "off@example.com", UserRole.MEMBER, UserStatus.DISABLED)));
        when(users.findById(gone)).thenReturn(Optional.empty());
        DatabaseRoleJwtConverter converter = new DatabaseRoleJwtConverter(users);

        assertThat(converter.convert(jwt(owner.toString())).getAuthorities()).extracting(Object::toString).containsExactly("ROLE_OWNER");
        assertThat(converter.convert(jwt(member.toString())).getAuthorities()).extracting(Object::toString).containsExactly("ROLE_MEMBER");
        assertThatThrownBy(() -> converter.convert(jwt(disabled.toString()))).isInstanceOf(InvalidBearerTokenException.class);
        assertThatThrownBy(() -> converter.convert(jwt(gone.toString()))).isInstanceOf(InvalidBearerTokenException.class);
        assertThatThrownBy(() -> converter.convert(jwt(null))).isInstanceOf(InvalidBearerTokenException.class);
        assertThatThrownBy(() -> converter.convert(jwt("nao-e-uuid"))).isInstanceOf(InvalidBearerTokenException.class);
    }

    @Test
    @DisplayName("a claim role do token nunca vale: um token de membro com role=OWNER ganha ROLE_MEMBER")
    void claimNaoAutoriza() {
        UserGateway users = mock(UserGateway.class);
        UUID member = UUID.randomUUID();
        when(users.findById(member)).thenReturn(Optional.of(User.member(member, "membro@example.com")));
        Jwt forged = Jwt.withTokenValue("t").header("alg", "HS256").subject(member.toString()).claim("role", "OWNER")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();

        assertThat(new DatabaseRoleJwtConverter(users).convert(forged).getAuthorities()).extracting(Object::toString)
                .containsExactly("ROLE_MEMBER");
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
