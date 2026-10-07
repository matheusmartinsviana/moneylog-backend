package com.moneylog.core.auth.app;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.moneylog.core.auth.app.gateways.PasswordVerifier;
import com.moneylog.core.auth.app.gateways.RefreshTokenGateway;
import com.moneylog.core.auth.app.gateways.TokenIssuer;
import com.moneylog.core.auth.app.gateways.TokenIssuer.AccessToken;
import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.app.usecase.LoginUseCase.LoginCommand;
import com.moneylog.core.auth.app.usecase.LoginUseCaseImpl;
import com.moneylog.core.auth.domain.RefreshToken;
import com.moneylog.core.auth.domain.User;
import com.moneylog.core.auth.exception.AuthExceptions.InvalidCredentialsException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.ClockTimeProvider;
import com.moneylog.core.sk.time.TimeProvider;

@ExtendWith(MockitoExtension.class)
@DisplayName("LoginUseCase")
class LoginUseCaseImplTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final UUID FAMILY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID TOKEN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @Mock
    private UserGateway users;
    @Mock
    private PasswordVerifier passwordVerifier;
    @Mock
    private TokenIssuer tokenIssuer;
    @Mock
    private RefreshTokenGateway refreshTokens;
    @Mock
    private IdGenerator ids;

    private final TimeProvider time = new ClockTimeProvider(Clock.fixed(NOW, java.time.ZoneOffset.UTC));
    private LoginUseCaseImpl useCase;

    @BeforeEach
    void setUp() {
        SessionOpener sessions = new SessionOpener(tokenIssuer, refreshTokens, ids, Duration.ofDays(30));
        useCase = new LoginUseCaseImpl(users, passwordVerifier, sessions, ids, time);
    }

    @Test
    @DisplayName("e-mail e senha certos abrem uma sessão com refresh gravado só pelo hash")
    void deveAbrirSessao() {
        when(users.findByEmail("dono@moneylog.test")).thenReturn(Optional.of(new User(USER_ID, "dono@moneylog.test")));
        when(passwordVerifier.verifyOwner("senha")).thenReturn(true);
        when(ids.next()).thenReturn(FAMILY_ID, TOKEN_ID);
        when(tokenIssuer.newRefreshTokenValue()).thenReturn("refresh-em-claro");
        when(tokenIssuer.issueAccessToken(USER_ID)).thenReturn(new AccessToken("jwt", NOW.plusSeconds(900)));

        AuthSession session = useCase.handle(new LoginCommand("  DONO@moneylog.test ", "senha"));

        assertThat(session.accessToken().value()).isEqualTo("jwt");
        assertThat(session.refreshToken()).isEqualTo("refresh-em-claro");
        assertThat(session.refreshExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
        ArgumentCaptor<RefreshToken> saved = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokens).insert(saved.capture());
        assertThat(saved.getValue().familyId()).isEqualTo(FAMILY_ID);
        assertThat(saved.getValue().tokenHash()).isEqualTo(RefreshTokenHash.of("refresh-em-claro")).hasSize(64)
                .doesNotContain("refresh-em-claro");
        verify(passwordVerifier, never()).verifyDummy(anyString());
    }

    @Test
    @DisplayName("e-mail desconhecido compara a senha com o hash fictício e responde como senha errada")
    void deveGastarOCustoDoBcryptComEmailDesconhecido() {
        when(users.findByEmail("outro@moneylog.test")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.handle(new LoginCommand("outro@moneylog.test", "senha")))
                .isInstanceOf(InvalidCredentialsException.class).hasMessage(new InvalidCredentialsException().getMessage());

        verify(passwordVerifier).verifyDummy("senha");
        verify(passwordVerifier, never()).verifyOwner(anyString());
        verify(refreshTokens, never()).insert(any());
    }

    @Test
    @DisplayName("senha errada responde com a mesma exceção e não grava nada")
    void deveRecusarSenhaErrada() {
        when(users.findByEmail("dono@moneylog.test")).thenReturn(Optional.of(new User(USER_ID, "dono@moneylog.test")));
        when(passwordVerifier.verifyOwner("errada")).thenReturn(false);

        assertThatThrownBy(() -> useCase.handle(new LoginCommand("dono@moneylog.test", "errada")))
                .isInstanceOf(InvalidCredentialsException.class).extracting("code").isEqualTo("INVALID_CREDENTIALS");

        verify(refreshTokens, never()).insert(any());
        verify(tokenIssuer, never()).issueAccessToken(any());
    }

    @Test
    @DisplayName("e-mail e senha ausentes são credenciais inválidas")
    void deveTratarCamposAusentes() {
        assertThatThrownBy(() -> useCase.handle(new LoginCommand(null, null))).isInstanceOf(InvalidCredentialsException.class);
        verify(passwordVerifier).verifyDummy("");
    }
}
