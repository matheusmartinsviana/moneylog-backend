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
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.moneylog.core.auth.app.gateways.RefreshTokenGateway;
import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.app.usecase.EnsureOwnerUseCase.EnsureOwnerCommand;
import com.moneylog.core.auth.app.usecase.EnsureOwnerUseCase.OwnerEnsured;
import com.moneylog.core.auth.app.usecase.EnsureOwnerUseCaseImpl;
import com.moneylog.core.auth.app.usecase.LogoutUseCase.LogoutCommand;
import com.moneylog.core.auth.app.usecase.LogoutUseCaseImpl;
import com.moneylog.core.auth.domain.RefreshToken;
import com.moneylog.core.auth.domain.User;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.ClockTimeProvider;

@ExtendWith(MockitoExtension.class)
@DisplayName("Logout e dono único")
class LogoutAndOwnerUseCasesTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final UUID FAMILY = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

    @Mock
    private RefreshTokenGateway refreshTokens;
    @Mock
    private UserGateway users;
    @Mock
    private IdGenerator ids;

    @Nested
    @DisplayName("logout")
    class Logout {

        private LogoutUseCaseImpl useCase() {
            return new LogoutUseCaseImpl(refreshTokens, new ClockTimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)));
        }

        @Test
        @DisplayName("revoga a família do refresh informado")
        void deveRevogarAFamilia() {
            String raw = "refresh";
            when(refreshTokens.findByHashForUpdate(RefreshTokenHash.of(raw))).thenReturn(Optional.of(
                    RefreshToken.issue(UUID.fromString("00000000-0000-0000-0000-0000000000a1"), USER_ID, FAMILY,
                            RefreshTokenHash.of(raw), NOW, NOW.plus(Duration.ofDays(30)))));

            useCase().handle(new LogoutCommand(raw));

            verify(refreshTokens).revokeFamily(FAMILY, NOW);
        }

        @Test
        @DisplayName("token desconhecido ou ausente não é erro e não revoga nada")
        void deveSerIdempotente() {
            when(refreshTokens.findByHashForUpdate(anyString())).thenReturn(Optional.empty());

            useCase().handle(new LogoutCommand("desconhecido"));
            useCase().handle(new LogoutCommand(null));
            useCase().handle(new LogoutCommand(" "));

            verify(refreshTokens, never()).revokeFamily(any(), any());
        }
    }

    @Nested
    @DisplayName("EnsureOwner (S-82)")
    class EnsureOwner {

        private EnsureOwnerUseCaseImpl useCase() {
            return new EnsureOwnerUseCaseImpl(users, ids);
        }

        @Test
        @DisplayName("tabela vazia: cria o dono com o e-mail normalizado e informa created")
        void deveCriarODono() {
            when(users.findOwner()).thenReturn(Optional.empty());
            when(ids.next()).thenReturn(USER_ID);

            OwnerEnsured result = useCase().handle(new EnsureOwnerCommand(" Dono@Moneylog.test "));

            assertThat(result.created()).isTrue();
            assertThat(result.previousEmail()).isNull();
            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(users).insert(saved.capture());
            assertThat(saved.getValue()).isEqualTo(new User(USER_ID, "dono@moneylog.test"));
        }

        @Test
        @DisplayName("dono existente com o mesmo e-mail: nada muda (idempotente)")
        void deveSerIdempotente() {
            when(users.findOwner()).thenReturn(Optional.of(new User(USER_ID, "dono@moneylog.test")));

            OwnerEnsured result = useCase().handle(new EnsureOwnerCommand("dono@moneylog.test"));

            assertThat(result.created()).isFalse();
            assertThat(result.previousEmail()).isNull();
            verify(users, never()).insert(any());
            verify(users, never()).updateEmail(any(), anyString());
        }

        @Test
        @DisplayName("e-mail diferente: atualiza mantendo o id e nunca insere uma segunda linha")
        void deveTrocarOEmailMantendoOId() {
            when(users.findOwner()).thenReturn(Optional.of(new User(USER_ID, "dono@moneylog.test")));

            OwnerEnsured result = useCase().handle(new EnsureOwnerCommand("novo@moneylog.test"));

            assertThat(result.created()).isFalse();
            assertThat(result.previousEmail()).isEqualTo("dono@moneylog.test");
            assertThat(result.owner()).isEqualTo(new User(USER_ID, "novo@moneylog.test"));
            verify(users).updateEmail(USER_ID, "novo@moneylog.test");
            verify(users, never()).insert(any());
        }

        @Test
        @DisplayName("e-mail em branco é recusado")
        void deveRecusarEmailEmBranco() {
            assertThatThrownBy(() -> useCase().handle(new EnsureOwnerCommand(" "))).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
