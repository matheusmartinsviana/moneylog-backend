package com.moneylog.infra.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.Authentication;

import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.domain.User;
import com.moneylog.core.notification.domain.SecurityEvent;
import com.moneylog.core.quickentry.app.gateways.QuickEntryTokenGateway;
import com.moneylog.core.quickentry.domain.QuickEntryScope;
import com.moneylog.core.quickentry.domain.QuickEntryToken;
import com.moneylog.core.quickentry.domain.QuickEntryTokenSecret;
import com.moneylog.core.quickentry.domain.TokenRevocationReason;

@DisplayName("Autenticação do token de lançamento rápido")
class QuickTokenAuthenticatorTest {

    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID TOKEN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    private final QuickEntryTokenGateway tokens = mock(QuickEntryTokenGateway.class);
    private final UserGateway users = mock(UserGateway.class);
    private final SecurityAlerts alerts = mock(SecurityAlerts.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final QuickEntryTokenSecret secret = QuickEntryTokenSecret.generate();
    private QuickTokenAuthenticator authenticator;

    @BeforeEach
    void montar() {
        QuickTokenRateLimiter limiter = new QuickTokenRateLimiter(clock,
                new QuickTokenRateLimiter.Settings(3, Duration.ofMinutes(5), 2, Duration.ofMinutes(1), 100));
        authenticator = new QuickTokenAuthenticator(tokens, users, new ClientIpResolver(List.of()), limiter, new QuickTokenSeenIps(), alerts,
                clock, new MoneylogQuickEntryProperties(null, null));
    }

    private QuickEntryToken token(Instant expiresAt, Instant revokedAt, Instant lastUsedAt, String lastUsedIp) {
        return new QuickEntryToken(TOKEN_ID, USER, ACCOUNT, "iPhone", secret.prefix(), secret.hash(), QuickEntryScope.QUICK_ENTRY, expiresAt,
                revokedAt, revokedAt == null ? null : TokenRevocationReason.USER, lastUsedAt, lastUsedIp, null, NOW.minus(Duration.ofDays(1)), 0L);
    }

    private QuickEntryToken activeToken() {
        return token(NOW.plus(Duration.ofDays(10)), null, null, null);
    }

    private void given(QuickEntryToken token, boolean userActive) {
        when(tokens.findByTokenHashForAuthentication(secret.hash())).thenReturn(Optional.of(token));
        User user = User.member(USER, "membro@example.com");
        when(users.findById(USER)).thenReturn(Optional.of(userActive ? user : user.disabled()));
    }

    private MockHttpServletRequest request(String authorization, String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/quick-entries");
        request.setRemoteAddr(ip);
        if (authorization != null) {
            request.addHeader("Authorization", authorization);
        }
        request.addHeader("User-Agent", "Shortcuts/1\r\nX");
        return request;
    }

    @Test
    @DisplayName("token ativo de usuário ativo autentica como o usuário do token e grava o uso com IP mascarado")
    void deveAutenticarEGravarUso() {
        given(activeToken(), true);

        Authentication authentication = authenticator.authenticate(request("Bearer " + secret.value(), "203.0.113.77"));

        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getName()).isEqualTo(USER.toString());
        assertThat(authentication).isInstanceOfSatisfying(QuickTokenAuthentication.class, quick -> {
            assertThat(quick.getPrincipal()).isEqualTo(new QuickTokenPrincipal(USER, TOKEN_ID, ACCOUNT));
            assertThat(quick.getAuthorities()).extracting(Object::toString).containsExactly("SCOPE_QUICK_ENTRY");
        });
        verify(tokens).findByTokenHashForAuthentication(secret.hash());
        verify(tokens).recordUse(USER, TOKEN_ID, NOW, NOW.minus(Duration.ofMinutes(1)), "203.0.113.0", "Shortcuts/1__X");
        verifyNoInteractions(alerts);
    }

    @Test
    @DisplayName("não grava de novo o uso quando o último foi há menos de um minuto")
    void deveGravarNoMaximoUmaVezPorMinuto() {
        given(token(NOW.plus(Duration.ofDays(10)), null, NOW.minusSeconds(30), "203.0.113.0"), true);

        authenticator.authenticate(request("Bearer " + secret.value(), "203.0.113.77"));

        verify(tokens, never()).recordUse(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("grava quando o último uso foi há mais de um minuto")
    void deveGravarDepoisDeUmMinuto() {
        given(token(NOW.plus(Duration.ofDays(10)), null, NOW.minusSeconds(61), "203.0.113.0"), true);

        authenticator.authenticate(request("Bearer " + secret.value(), "203.0.113.77"));

        verify(tokens).recordUse(eq(USER), eq(TOKEN_ID), eq(NOW), any(), eq("203.0.113.0"), anyString());
    }

    @Test
    @DisplayName("falha ao gravar o uso não derruba a autenticação")
    void devePermitirFalhaNaGravacaoDoUso() {
        given(activeToken(), true);
        when(tokens.recordUse(any(), any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("banco"));

        assertThat(authenticator.authenticate(request("Bearer " + secret.value(), "203.0.113.77")).isAuthenticated()).isTrue();
    }

    @Test
    @DisplayName("sem cabeçalho, esquema errado, cabeçalho duplicado, formato errado e hash desconhecido dão a mesma exceção")
    void deveRecusarEntradasInvalidas() {
        when(tokens.findByTokenHashForAuthentication(anyString())).thenReturn(Optional.empty());
        MockHttpServletRequest duplicate = request("Bearer " + secret.value(), "203.0.113.1");
        duplicate.addHeader("Authorization", "Bearer " + secret.value());

        for (MockHttpServletRequest request : List.of(request(null, "203.0.113.1"), request("Basic abc", "203.0.113.2"),
                request("bearer " + secret.value(), "203.0.113.3"), request("Bearer  " + secret.value(), "203.0.113.4"),
                request("Bearer mlq_curto", "203.0.113.5"), request("Bearer " + secret.value(), "203.0.113.6"), duplicate)) {
            assertThatThrownBy(() -> authenticator.authenticate(request)).isExactlyInstanceOf(QuickTokenInvalidException.class);
        }
        verify(tokens, times(1)).findByTokenHashForAuthentication(secret.hash());
        verify(tokens, never()).recordUse(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("expirado, revogado e usuário desativado dão a mesma exceção e nunca gravam uso")
    void deveRecusarTokenInativoOuUsuarioDesativado() {
        given(token(NOW.minusSeconds(1), null, null, null), true);
        assertThatThrownBy(() -> authenticator.authenticate(request("Bearer " + secret.value(), "203.0.113.11")))
                .isExactlyInstanceOf(QuickTokenInvalidException.class);

        given(token(NOW.plus(Duration.ofDays(5)), NOW.minusSeconds(5), null, null), true);
        assertThatThrownBy(() -> authenticator.authenticate(request("Bearer " + secret.value(), "203.0.113.12")))
                .isExactlyInstanceOf(QuickTokenInvalidException.class);

        given(activeToken(), false);
        assertThatThrownBy(() -> authenticator.authenticate(request("Bearer " + secret.value(), "203.0.113.13")))
                .isExactlyInstanceOf(QuickTokenInvalidException.class);

        verify(tokens, never()).recordUse(any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a terceira falha do mesmo IP gera o alerta de rajada sem o segredo; a quarta tentativa é 429 antes de consultar o banco")
    void deveAlertarRajadaEBloquear() {
        when(tokens.findByTokenHashForAuthentication(anyString())).thenReturn(Optional.empty());
        String ip = "198.51.100.20";

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> authenticator.authenticate(request("Bearer " + secret.value(), ip)))
                    .isExactlyInstanceOf(QuickTokenInvalidException.class);
        }
        verify(alerts, times(1)).raise(SecurityEvent.QUICK_TOKEN_FAILURE_BURST, ip, null);

        assertThatThrownBy(() -> authenticator.authenticate(request("Bearer " + secret.value(), ip)))
                .isInstanceOf(TooManyAttemptsException.class);
        verify(tokens, times(3)).findByTokenHashForAuthentication(anyString());
    }

    @Test
    @DisplayName("token válido a partir de um IP bloqueado por falhas também recebe 429")
    void deveBloquearIpMesmoComTokenValido() {
        when(tokens.findByTokenHashForAuthentication(anyString())).thenReturn(Optional.empty());
        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> authenticator.authenticate(request(null, "198.51.100.21"))).isInstanceOf(QuickTokenInvalidException.class);
        }
        given(activeToken(), true);

        assertThatThrownBy(() -> authenticator.authenticate(request("Bearer " + secret.value(), "198.51.100.21")))
                .isInstanceOf(TooManyAttemptsException.class);
    }

    @Test
    @DisplayName("acima do limite por token responde 429 sem invalidar o token, e o 429 não conta como falha de IP")
    void deveLimitarChamadasPorToken() {
        given(activeToken(), true);
        String ip = "203.0.113.30";
        authenticator.authenticate(request("Bearer " + secret.value(), ip));
        authenticator.authenticate(request("Bearer " + secret.value(), ip));

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> authenticator.authenticate(request("Bearer " + secret.value(), ip)))
                    .isInstanceOf(TooManyAttemptsException.class).isNotInstanceOf(QuickTokenInvalidException.class);
        }
        verify(alerts, never()).raise(any(), any(), any());
    }

    @Test
    @DisplayName("IP diferente do último gravado alerta o dono do token com o IP mascarado; o mesmo IP não")
    void deveAlertarIpNovo() {
        given(token(NOW.plus(Duration.ofDays(10)), null, NOW.minusSeconds(120), "203.0.113.0"), true);

        authenticator.authenticate(request("Bearer " + secret.value(), "203.0.113.200"));
        verifyNoInteractions(alerts);

        authenticator.authenticate(request("Bearer " + secret.value(), "198.51.100.9"));
        verify(alerts).raiseForUser(USER, SecurityEvent.QUICK_TOKEN_NEW_IP, "198.51.100.0", null);
    }

    @Test
    @DisplayName("o primeiro uso de um token recém-criado não alerta")
    void naoDeveAlertarPrimeiroUso() {
        given(activeToken(), true);

        authenticator.authenticate(request("Bearer " + secret.value(), "198.51.100.9"));

        verify(alerts, never()).raiseForUser(any(), any(), any(), isNull());
    }
}
