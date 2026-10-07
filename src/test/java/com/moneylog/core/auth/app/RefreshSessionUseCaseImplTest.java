package com.moneylog.core.auth.app;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.moneylog.core.auth.app.gateways.RefreshTokenGateway;
import com.moneylog.core.auth.app.gateways.TokenIssuer;
import com.moneylog.core.auth.app.gateways.TokenIssuer.AccessToken;
import com.moneylog.core.auth.app.usecase.RefreshSessionUseCase.RefreshCommand;
import com.moneylog.core.auth.app.usecase.RefreshSessionUseCaseImpl;
import com.moneylog.core.auth.domain.RefreshToken;
import com.moneylog.core.auth.exception.AuthExceptions.RefreshInvalidException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.ClockTimeProvider;

@ExtendWith(MockitoExtension.class)
@DisplayName("RefreshSessionUseCase (S-83)")
class RefreshSessionUseCaseImplTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final UUID FAMILY = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID OLD_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID NEW_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final String RAW = "refresh-antigo";
    private static final String HASH = RefreshTokenHash.of(RAW);

    @Mock
    private RefreshTokenGateway refreshTokens;
    @Mock
    private TokenIssuer tokenIssuer;
    @Mock
    private IdGenerator ids;

    private RefreshSessionUseCaseImpl useCase;

    @BeforeEach
    void setUp() {
        SessionOpener sessions = new SessionOpener(tokenIssuer, refreshTokens, ids, Duration.ofDays(30));
        useCase = new RefreshSessionUseCaseImpl(refreshTokens, sessions,
                new ClockTimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)), Duration.ofSeconds(30));
    }

    private RefreshToken token(Instant revokedAt, Instant replacedAt, Instant expiresAt) {
        return new RefreshToken(OLD_ID, USER_ID, FAMILY, HASH, NOW.minusSeconds(3600), expiresAt, revokedAt,
                replacedAt == null ? null : UUID.fromString("00000000-0000-0000-0000-0000000000a9"), replacedAt);
    }

    private void stubIssuer() {
        when(ids.next()).thenReturn(NEW_ID);
        when(tokenIssuer.newRefreshTokenValue()).thenReturn("refresh-novo");
        when(tokenIssuer.issueAccessToken(USER_ID)).thenReturn(new AccessToken("jwt", NOW.plusSeconds(900)));
    }

    @Nested
    @DisplayName("token ainda não usado")
    class NaoUsado {

        @Test
        @DisplayName("rotaciona: novo token na mesma família e o antigo fica substituído")
        void deveRotacionar() {
            when(refreshTokens.findByHashForUpdate(HASH)).thenReturn(Optional.of(token(null, null, NOW.plusSeconds(1000))));
            stubIssuer();

            AuthSession session = useCase.handle(new RefreshCommand(RAW));

            assertThat(session.refreshToken()).isEqualTo("refresh-novo");
            verify(refreshTokens).markReplaced(OLD_ID, NEW_ID, NOW);
            verify(refreshTokens, never()).revokeFamily(any(), any());
        }
    }

    @Nested
    @DisplayName("token já substituído")
    class Substituido {

        @Test
        @DisplayName("dentro da janela de 30 s: outro sucessor da mesma família, sem revogar")
        void deveTolerarDentroDaJanela() {
            when(refreshTokens.findByHashForUpdate(HASH))
                    .thenReturn(Optional.of(token(null, NOW.minusSeconds(10), NOW.plusSeconds(1000))));
            stubIssuer();

            AuthSession session = useCase.handle(new RefreshCommand(RAW));

            assertThat(session.accessToken().value()).isEqualTo("jwt");
            verify(refreshTokens, never()).revokeFamily(any(), any());
            verify(refreshTokens, never()).markReplaced(any(), any(), any());
        }

        @Test
        @DisplayName("exatamente 30 s depois ainda é tolerado")
        void deveTolerarNoLimiteDaJanela() {
            when(refreshTokens.findByHashForUpdate(HASH))
                    .thenReturn(Optional.of(token(null, NOW.minusSeconds(30), NOW.plusSeconds(1000))));
            stubIssuer();

            assertThat(useCase.handle(new RefreshCommand(RAW)).refreshToken()).isEqualTo("refresh-novo");
        }

        @Test
        @DisplayName("31 s depois é reuso: revoga a família inteira e recusa")
        void deveRevogarAFamiliaNoReuso() {
            when(refreshTokens.findByHashForUpdate(HASH))
                    .thenReturn(Optional.of(token(null, NOW.minusSeconds(31), NOW.plusSeconds(1000))));

            assertThatThrownBy(() -> useCase.handle(new RefreshCommand(RAW))).isInstanceOf(RefreshInvalidException.class);

            verify(refreshTokens).revokeFamily(FAMILY, NOW);
            verify(refreshTokens, never()).insert(any());
        }
    }

    @Nested
    @DisplayName("token recusado sem tolerância")
    class Recusado {

        @Test
        @DisplayName("revogado por logout ou reuso é recusado mesmo dentro da janela e não revoga de novo")
        void deveRecusarRevogado() {
            when(refreshTokens.findByHashForUpdate(HASH))
                    .thenReturn(Optional.of(token(NOW.minusSeconds(5), NOW.minusSeconds(2), NOW.plusSeconds(1000))));

            assertThatThrownBy(() -> useCase.handle(new RefreshCommand(RAW))).isInstanceOf(RefreshInvalidException.class);

            verify(refreshTokens, never()).revokeFamily(any(), any());
            verify(refreshTokens, never()).insert(any());
        }

        @Test
        @DisplayName("expirado é recusado")
        void deveRecusarExpirado() {
            when(refreshTokens.findByHashForUpdate(HASH)).thenReturn(Optional.of(token(null, null, NOW)));

            assertThatThrownBy(() -> useCase.handle(new RefreshCommand(RAW))).isInstanceOf(RefreshInvalidException.class);
        }

        @Test
        @DisplayName("desconhecido, vazio ou ausente é recusado")
        void deveRecusarDesconhecido() {
            when(refreshTokens.findByHashForUpdate(HASH)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> useCase.handle(new RefreshCommand(RAW))).isInstanceOf(RefreshInvalidException.class);
            assertThatThrownBy(() -> useCase.handle(new RefreshCommand(" "))).isInstanceOf(RefreshInvalidException.class);
            assertThatThrownBy(() -> useCase.handle(new RefreshCommand(null))).isInstanceOf(RefreshInvalidException.class);
        }
    }
}
