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
import com.moneylog.core.auth.domain.UserRole;
import com.moneylog.core.auth.domain.UserStatus;
import com.moneylog.core.auth.exception.OwnerEmailConflictException;
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
            return new EnsureOwnerUseCaseImpl(users, ids, refreshTokens, new ClockTimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)));
        }

        @Test
        @DisplayName("tabela vazia: cria o dono com o e-mail normalizado e informa created")
        void deveCriarODono() {
            when(users.findByRole(UserRole.OWNER)).thenReturn(Optional.empty());
            when(ids.next()).thenReturn(USER_ID);

            OwnerEnsured result = useCase().handle(new EnsureOwnerCommand(" Dono@Moneylog.test "));

            assertThat(result.created()).isTrue();
            ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
            verify(users).insert(saved.capture());
            assertThat(saved.getValue()).isEqualTo(User.owner(USER_ID, "dono@moneylog.test"));
        }

        @Test
        @DisplayName("dono existente com o mesmo e-mail: nada muda (idempotente)")
        void deveSerIdempotente() {
            when(users.findByRole(UserRole.OWNER)).thenReturn(Optional.of(User.owner(USER_ID, "dono@moneylog.test")));

            OwnerEnsured result = useCase().handle(new EnsureOwnerCommand("dono@moneylog.test"));

            assertThat(result.created()).isFalse();
            verify(users, never()).insert(any());
        }

        @Test
        @DisplayName("e-mail do ambiente diferente do dono, mesmo livre: falha alto, sem renomear nem inserir")
        void deveFalharQuandoEmailDivergeDoDono() {
            when(users.findByRole(UserRole.OWNER)).thenReturn(Optional.of(User.owner(USER_ID, "dono@moneylog.test")));

            assertThatThrownBy(() -> useCase().handle(new EnsureOwnerCommand("novo@moneylog.test")))
                    .isInstanceOf(OwnerEmailConflictException.class).hasMessageContaining("MONEYLOG_OWNER_EMAIL");

            verify(users, never()).insert(any());
            verify(users, never()).updateRole(any(), any(), any());
            verify(refreshTokens, never()).revokeAllByUser(any(), any());
        }

        @Test
        @DisplayName("sem dono e com usuário do e-mail do ambiente: promove esse usuário sem criar outro nem renomear")
        void devePromoverUsuarioExistenteQuandoNaoHaDono() {
            UUID memberId = UUID.fromString("00000000-0000-0000-0000-0000000000b7");
            when(users.findByRole(UserRole.OWNER)).thenReturn(Optional.empty());
            when(users.findByEmail("dono@moneylog.test")).thenReturn(Optional.of(User.member(memberId, "dono@moneylog.test")));

            OwnerEnsured result = useCase().handle(new EnsureOwnerCommand("dono@moneylog.test"));

            assertThat(result.promoted()).isTrue();
            assertThat(result.created()).isFalse();
            assertThat(result.owner()).isEqualTo(User.owner(memberId, "dono@moneylog.test"));
            verify(users).updateRole(memberId, UserRole.OWNER, NOW);
            verify(users, never()).insert(any());
        }

        @Test
        @DisplayName("sem dono e com usuário desativado no e-mail do ambiente: falha alto e não promove")
        void naoDevePromoverUsuarioDesativado() {
            when(users.findByRole(UserRole.OWNER)).thenReturn(Optional.empty());
            when(users.findByEmail("dono@moneylog.test")).thenReturn(Optional.of(
                    new User(USER_ID, "dono@moneylog.test", UserRole.MEMBER, UserStatus.DISABLED)));

            assertThatThrownBy(() -> useCase().handle(new EnsureOwnerCommand("dono@moneylog.test")))
                    .isInstanceOf(IllegalStateException.class);

            verify(users, never()).updateRole(any(), any(), any());
        }

        @Test
        @DisplayName("dono existente e o e-mail do ambiente é de outro usuário: falha alto, sem renomear nem promover ninguém")
        void deveFalharQuandoEmailDoAmbienteEDeOutroUsuario() {
            when(users.findByRole(UserRole.OWNER)).thenReturn(Optional.of(User.owner(USER_ID, "dono@moneylog.test")));

            assertThatThrownBy(() -> useCase().handle(new EnsureOwnerCommand("membro@moneylog.test")))
                    .isInstanceOf(OwnerEmailConflictException.class).hasMessageContaining("MONEYLOG_OWNER_EMAIL");

            verify(users, never()).updateRole(any(), any(), any());
            verify(users, never()).insert(any());
            verify(refreshTokens, never()).revokeAllByUser(any(), any());
        }

        @Test
        @DisplayName("e-mail em branco é recusado")
        void deveRecusarEmailEmBranco() {
            assertThatThrownBy(() -> useCase().handle(new EnsureOwnerCommand(" "))).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
