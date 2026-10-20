package com.moneylog.core.auth.app;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.moneylog.core.auth.app.gateways.RefreshTokenGateway;
import com.moneylog.core.auth.app.gateways.TokenIssuer;
import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.domain.User;
import com.moneylog.core.auth.app.gateways.TokenIssuer.AccessToken;
import com.moneylog.core.auth.app.usecase.PurgeExpiredRefreshTokensUseCase.PurgeCommand;
import com.moneylog.core.auth.app.usecase.PurgeExpiredRefreshTokensUseCaseImpl;
import com.moneylog.core.auth.app.usecase.RefreshSessionUseCase.RefreshCommand;
import com.moneylog.core.auth.app.usecase.RefreshSessionUseCaseImpl;
import com.moneylog.core.auth.domain.RefreshToken;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.ClockTimeProvider;

@ExtendWith(MockitoExtension.class)
@DisplayName("Sessão com teto absoluto e limpeza do refresh (B7)")
class SessaoComTetoAbsolutoTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final UUID FAMILY = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID OLD_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID NEW_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final String RAW = "refresh-antigo";

    @Mock
    private RefreshTokenGateway refreshTokens;
    @Mock
    private TokenIssuer tokenIssuer;
    @Mock
    private IdGenerator ids;
    @Mock
    private UserGateway users;

    private RefreshSessionUseCaseImpl useCase() {
        SessionOpener sessions = new SessionOpener(tokenIssuer, refreshTokens, ids, Duration.ofDays(30), Duration.ofDays(90));
        return new RefreshSessionUseCaseImpl(users, refreshTokens, sessions, new ClockTimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)),
                Duration.ofSeconds(30));
    }

    private void stubIssuer() {
        lenient().when(refreshTokens.findUserIdByHash(RefreshTokenHash.of(RAW))).thenReturn(java.util.Optional.of(USER_ID));
        lenient().when(users.findByIdLockingShared(USER_ID)).thenReturn(java.util.Optional.of(User.owner(USER_ID, "dono@moneylog.test")));
        when(ids.next()).thenReturn(NEW_ID);
        when(tokenIssuer.newRefreshTokenValue()).thenReturn("refresh-novo");
        when(tokenIssuer.issueAccessToken(any(User.class))).thenReturn(new AccessToken("jwt", NOW.plusSeconds(900)));
    }

    private RefreshToken tokenDeUmaFamiliaQueComecou(Instant familyStartedAt, Instant expiresAt) {
        return new RefreshToken(OLD_ID, USER_ID, FAMILY, RefreshTokenHash.of(RAW), NOW.minusSeconds(3600), expiresAt, null, null, null,
                familyStartedAt);
    }

    @Test
    @DisplayName("rotação no meio da vida da família: o sucessor ganha +30 dias e herda o início da família")
    void deveManterOsTrintaDiasAntesDoTeto() {
        Instant started = NOW.minus(Duration.ofDays(10));
        when(refreshTokens.findByHashForUpdate(RefreshTokenHash.of(RAW)))
                .thenReturn(java.util.Optional.of(tokenDeUmaFamiliaQueComecou(started, NOW.plusSeconds(1000))));
        stubIssuer();

        AuthSession session = useCase().handle(new RefreshCommand(RAW));

        assertThat(session.refreshExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
        ArgumentCaptor<RefreshToken> inserted = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokens).insert(inserted.capture());
        assertThat(inserted.getValue().familyStartedAt()).isEqualTo(started);
    }

    @Test
    @DisplayName("rotação perto do teto: o sucessor expira em início + 90 dias, não em +30 dias")
    void deveLimitarAExpiracaoAoTeto() {
        Instant started = NOW.minus(Duration.ofDays(80));
        when(refreshTokens.findByHashForUpdate(RefreshTokenHash.of(RAW)))
                .thenReturn(java.util.Optional.of(tokenDeUmaFamiliaQueComecou(started, NOW.plusSeconds(1000))));
        stubIssuer();

        AuthSession session = useCase().handle(new RefreshCommand(RAW));

        assertThat(session.refreshExpiresAt()).isEqualTo(started.plus(Duration.ofDays(90)));
        assertThat(session.refreshExpiresAt()).isBefore(NOW.plus(Duration.ofDays(30)));
    }

    @Test
    @DisplayName("login abre uma família nova: começa agora e vive 30 dias")
    void loginComecaAFamiliaAgora() {
        stubIssuer();
        SessionOpener sessions = new SessionOpener(tokenIssuer, refreshTokens, ids, Duration.ofDays(30), Duration.ofDays(90));

        AuthSession session = sessions.open(User.owner(USER_ID, "dono@moneylog.test"), FAMILY, NOW);

        assertThat(session.refreshExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
        ArgumentCaptor<RefreshToken> inserted = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokens).insert(inserted.capture());
        assertThat(inserted.getValue().familyStartedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("a limpeza apaga o que expirou antes de agora − retenção")
    void deveLimparOsExpirados() {
        when(refreshTokens.deleteExpiredBefore(any())).thenReturn(4);

        int deleted = new PurgeExpiredRefreshTokensUseCaseImpl(refreshTokens, new ClockTimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)))
                .handle(new PurgeCommand(Duration.ofDays(7)));

        assertThat(deleted).isEqualTo(4);
        verify(refreshTokens).deleteExpiredBefore(NOW.minus(Duration.ofDays(7)));
    }
}
