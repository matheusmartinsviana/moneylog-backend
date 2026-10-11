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

import com.moneylog.core.auth.app.gateways.PasswordVerifier;
import com.moneylog.core.auth.app.gateways.RefreshTokenGateway;
import com.moneylog.core.auth.app.gateways.TokenIssuer;
import com.moneylog.core.auth.app.gateways.TokenIssuer.AccessToken;
import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.app.usecase.AuthStatusUseCaseImpl;
import com.moneylog.core.auth.app.usecase.EnsureOwnerUseCase.EnsureOwnerCommand;
import com.moneylog.core.auth.app.usecase.EnsureOwnerUseCase.OwnerEnsured;
import com.moneylog.core.auth.app.usecase.EnsureOwnerUseCaseImpl;
import com.moneylog.core.auth.app.usecase.LoginUseCase.LoginCommand;
import com.moneylog.core.auth.app.usecase.LoginUseCaseImpl;
import com.moneylog.core.auth.app.usecase.PrepareFirstAccessUseCase.FirstAccessPrepared;
import com.moneylog.core.auth.app.usecase.PrepareFirstAccessUseCase.PrepareFirstAccessCommand;
import com.moneylog.core.auth.app.usecase.PrepareFirstAccessUseCaseImpl;
import com.moneylog.core.auth.app.usecase.SetupPasswordUseCase.SetupPasswordCommand;
import com.moneylog.core.auth.app.usecase.SetupPasswordUseCaseImpl;
import com.moneylog.core.auth.domain.OwnerAccess;
import com.moneylog.core.auth.domain.PasswordPolicy;
import com.moneylog.core.auth.domain.SetupCode;
import com.moneylog.core.auth.domain.User;
import com.moneylog.core.auth.exception.AuthExceptions.InvalidCredentialsException;
import com.moneylog.core.auth.exception.AuthExceptions.InvalidSetupCodeException;
import com.moneylog.core.auth.exception.AuthExceptions.WeakPasswordException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.ClockTimeProvider;
import com.moneylog.core.sk.time.TimeProvider;

@ExtendWith(MockitoExtension.class)
@DisplayName("Primeiro acesso do dono (ADR-015)")
class FirstAccessUseCasesTest {

    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");
    private static final String EMAIL = "dono@moneylog.test";
    private static final String CODE = "codigo-de-uso-unico-1234567890";
    private static final UUID FAMILY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID TOKEN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @Mock
    private UserGateway users;
    @Mock
    private PasswordVerifier verifier;
    @Mock
    private RefreshTokenGateway refreshTokens;
    @Mock
    private TokenIssuer tokenIssuer;
    @Mock
    private IdGenerator ids;

    private final TimeProvider time = new ClockTimeProvider(Clock.fixed(NOW, ZoneOffset.UTC));

    private static OwnerAccess pendente(String code, Instant expiresAt) {
        return new OwnerAccess(null, true, SetupCode.hash(code), expiresAt);
    }

    private SetupPasswordUseCaseImpl setup() {
        SessionOpener sessions = new SessionOpener(tokenIssuer, refreshTokens, ids, Duration.ofDays(30));
        return new SetupPasswordUseCaseImpl(users, verifier, refreshTokens, sessions, ids, time);
    }

    @Nested
    @DisplayName("PasswordPolicy")
    class Politica {

        @Test
        @DisplayName("aceita 12 caracteres ou mais")
        void deveAceitar() {
            PasswordPolicy.check("doze-chars-ok", EMAIL);
        }

        @Test
        @DisplayName("recusa curta, nula, igual ao e-mail (sem diferenciar caixa) e acima de 72 bytes")
        void deveRecusar() {
            assertThatThrownBy(() -> PasswordPolicy.check("curta", EMAIL)).isInstanceOf(WeakPasswordException.class);
            assertThatThrownBy(() -> PasswordPolicy.check("onzechars-1", EMAIL)).isInstanceOf(WeakPasswordException.class);
            assertThatThrownBy(() -> PasswordPolicy.check(null, EMAIL)).isInstanceOf(WeakPasswordException.class);
            assertThatThrownBy(() -> PasswordPolicy.check("DONO@moneylog.test", EMAIL)).isInstanceOf(WeakPasswordException.class);
            assertThatThrownBy(() -> PasswordPolicy.check("a".repeat(73), EMAIL)).isInstanceOf(WeakPasswordException.class);
        }

        @Test
        @DisplayName("a mensagem não ecoa a senha")
        void naoDeveVazar() {
            assertThatThrownBy(() -> PasswordPolicy.check("segredinho", EMAIL)).hasMessageNotContaining("segredinho");
        }
    }

    @Nested
    @DisplayName("SetupCode")
    class Codigo {

        @Test
        @DisplayName("gera códigos distintos e o hash não contém o código")
        void deveGerar() {
            String a = SetupCode.generate();
            assertThat(a).isNotEqualTo(SetupCode.generate()).hasSizeGreaterThanOrEqualTo(32);
            assertThat(SetupCode.hash(a)).hasSize(64).doesNotContain(a);
            assertThat(SetupCode.sameHash(SetupCode.hash(a), SetupCode.hash(" " + a + " "))).isTrue();
            assertThat(SetupCode.sameHash(SetupCode.hash(a), null)).isFalse();
        }
    }

    @Nested
    @DisplayName("SetupPassword")
    class Setup {

        private void donoPendente(OwnerAccess access) {
            when(users.findOwner()).thenReturn(Optional.of(new User(USER_ID, EMAIL)));
            when(users.findAccess(USER_ID)).thenReturn(Optional.of(access));
            when(verifier.hasEnvironmentHash()).thenReturn(false);
        }

        @Test
        @DisplayName("caminho feliz: grava o hash, consome o código, revoga todas as sessões e abre a sessão")
        void deveDefinirSenha() {
            donoPendente(pendente(CODE, NOW.plusSeconds(60)));
            when(verifier.encode("uma-senha-forte-1")).thenReturn("$hash");
            when(users.completeSetup(USER_ID, SetupCode.hash(CODE), "$hash", NOW)).thenReturn(true);
            when(ids.next()).thenReturn(FAMILY_ID, TOKEN_ID);
            when(tokenIssuer.newRefreshTokenValue()).thenReturn("refresh");
            when(tokenIssuer.issueAccessToken(USER_ID)).thenReturn(new AccessToken("jwt", NOW.plusSeconds(900)));

            AuthSession session = setup().handle(new SetupPasswordCommand(" Dono@Moneylog.test ", CODE, "uma-senha-forte-1"));

            assertThat(session.accessToken().value()).isEqualTo("jwt");
            verify(refreshTokens).revokeAllByUser(USER_ID, NOW);
            ArgumentCaptor<com.moneylog.core.auth.domain.RefreshToken> saved =
                    ArgumentCaptor.forClass(com.moneylog.core.auth.domain.RefreshToken.class);
            verify(refreshTokens).insert(saved.capture());
            assertThat(saved.getValue().tokenHash()).isNotEqualTo("refresh");
        }

        @Test
        @DisplayName("código errado: 401 genérico, nada é gravado")
        void deveRecusarCodigoErrado() {
            donoPendente(pendente(CODE, NOW.plusSeconds(60)));

            assertThatThrownBy(() -> setup().handle(new SetupPasswordCommand(EMAIL, "outro-codigo", "uma-senha-forte-1")))
                    .isInstanceOf(InvalidSetupCodeException.class);
            verify(users, never()).completeSetup(any(), anyString(), anyString(), any());
            verify(refreshTokens, never()).revokeAllByUser(any(), any());
        }

        @Test
        @DisplayName("e-mail que não é do dono: mesma recusa")
        void deveRecusarEmailErrado() {
            donoPendente(pendente(CODE, NOW.plusSeconds(60)));

            assertThatThrownBy(() -> setup().handle(new SetupPasswordCommand("outro@x.com", CODE, "uma-senha-forte-1")))
                    .isInstanceOf(InvalidSetupCodeException.class);
            assertThatThrownBy(() -> setup().handle(new SetupPasswordCommand(null, CODE, "uma-senha-forte-1")))
                    .isInstanceOf(InvalidSetupCodeException.class);
        }

        @Test
        @DisplayName("código expirado: recusa")
        void deveRecusarCodigoExpirado() {
            donoPendente(pendente(CODE, NOW));

            assertThatThrownBy(() -> setup().handle(new SetupPasswordCommand(EMAIL, CODE, "uma-senha-forte-1")))
                    .isInstanceOf(InvalidSetupCodeException.class);
        }

        @Test
        @DisplayName("código já usado (hash apagado): recusa")
        void deveRecusarCodigoUsado() {
            donoPendente(new OwnerAccess(null, true, null, null));

            assertThatThrownBy(() -> setup().handle(new SetupPasswordCommand(EMAIL, CODE, "uma-senha-forte-1")))
                    .isInstanceOf(InvalidSetupCodeException.class);
        }

        @Test
        @DisplayName("dono que já tem senha no banco: recusa, mesmo com o código certo")
        void deveRecusarSeJaTemSenha() {
            donoPendente(new OwnerAccess("$hash", false, SetupCode.hash(CODE), NOW.plusSeconds(60)));

            assertThatThrownBy(() -> setup().handle(new SetupPasswordCommand(EMAIL, CODE, "uma-senha-forte-1")))
                    .isInstanceOf(InvalidSetupCodeException.class);
            verify(users, never()).completeSetup(any(), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("senha fraca com o código certo: 422 e o código continua valendo")
        void deveRecusarSenhaFracaSemConsumirCodigo() {
            donoPendente(pendente(CODE, NOW.plusSeconds(60)));

            assertThatThrownBy(() -> setup().handle(new SetupPasswordCommand(EMAIL, CODE, "curta")))
                    .isInstanceOf(WeakPasswordException.class);
            verify(users, never()).completeSetup(any(), anyString(), anyString(), any());
            verify(refreshTokens, never()).revokeAllByUser(any(), any());
        }

        @Test
        @DisplayName("perdeu a corrida (código consumido por outro pedido): recusa e não revoga nada")
        void deveRecusarSeConsumidoEmParalelo() {
            donoPendente(pendente(CODE, NOW.plusSeconds(60)));
            when(verifier.encode(anyString())).thenReturn("$hash");
            when(users.completeSetup(USER_ID, SetupCode.hash(CODE), "$hash", NOW)).thenReturn(false);

            assertThatThrownBy(() -> setup().handle(new SetupPasswordCommand(EMAIL, CODE, "uma-senha-forte-1")))
                    .isInstanceOf(InvalidSetupCodeException.class);
            verify(refreshTokens, never()).revokeAllByUser(any(), any());
        }
    }

    @Nested
    @DisplayName("PrepareFirstAccess")
    class Preparar {

        private PrepareFirstAccessUseCaseImpl useCase() {
            return new PrepareFirstAccessUseCaseImpl(users, verifier, time);
        }

        @Test
        @DisplayName("sem senha e sem hash no ambiente: gera o código, grava só o hash e o devolve uma vez")
        void deveGerarCodigo() {
            when(users.findOwner()).thenReturn(Optional.of(new User(USER_ID, EMAIL)));
            when(users.findAccess(USER_ID)).thenReturn(Optional.of(new OwnerAccess(null, false, null, null)));
            when(verifier.hasEnvironmentHash()).thenReturn(false);

            FirstAccessPrepared result = useCase().handle(new PrepareFirstAccessCommand(null, Duration.ofHours(24)));

            assertThat(result.setupRequired()).isTrue();
            assertThat(result.generatedCode()).isNotBlank();
            verify(users).storeSetupCode(USER_ID, SetupCode.hash(result.generatedCode()), NOW.plus(Duration.ofHours(24)), NOW);
        }

        @Test
        @DisplayName("código informado pelo operador: grava o hash dele e não devolve nada para imprimir")
        void deveUsarCodigoInformado() {
            when(users.findOwner()).thenReturn(Optional.of(new User(USER_ID, EMAIL)));
            when(users.findAccess(USER_ID)).thenReturn(Optional.of(new OwnerAccess(null, true, null, null)));
            when(verifier.hasEnvironmentHash()).thenReturn(true);

            FirstAccessPrepared result = useCase().handle(new PrepareFirstAccessCommand(" " + CODE + " ", Duration.ofHours(1)));

            assertThat(result.setupRequired()).isTrue();
            assertThat(result.generatedCode()).isNull();
            verify(users).storeSetupCode(USER_ID, SetupCode.hash(CODE), NOW.plus(Duration.ofHours(1)), NOW);
        }

        @Test
        @DisplayName("código informado curto demais: não sobe")
        void deveRecusarCodigoCurto() {
            when(users.findOwner()).thenReturn(Optional.of(new User(USER_ID, EMAIL)));
            when(users.findAccess(USER_ID)).thenReturn(Optional.of(new OwnerAccess(null, true, null, null)));
            when(verifier.hasEnvironmentHash()).thenReturn(false);

            assertThatThrownBy(() -> useCase().handle(new PrepareFirstAccessCommand("curto", Duration.ofHours(1))))
                    .isInstanceOf(IllegalStateException.class).hasMessageNotContaining("curto");
        }

        @Test
        @DisplayName("retrocompatibilidade (hash no ambiente, sem redefinição) ou senha no banco: nenhum código é criado")
        void naoDeveCriarCodigoSeNaoPrecisa() {
            when(users.findOwner()).thenReturn(Optional.of(new User(USER_ID, EMAIL)));
            when(users.findAccess(USER_ID)).thenReturn(Optional.of(new OwnerAccess(null, false, null, null)));
            when(verifier.hasEnvironmentHash()).thenReturn(true);

            assertThat(useCase().handle(new PrepareFirstAccessCommand(null, Duration.ofHours(1))).setupRequired()).isFalse();
            verify(users, never()).storeSetupCode(any(), anyString(), any(), any());
        }
    }

    @Nested
    @DisplayName("AuthStatus")
    class Status {

        @Test
        @DisplayName("true sem senha e sem hash no ambiente; true com redefinição pendente; false com hash do ambiente ou senha no banco")
        void deveRefletirOEstado() {
            var useCase = new AuthStatusUseCaseImpl(users, verifier);
            when(users.findOwner()).thenReturn(Optional.of(new User(USER_ID, EMAIL)));
            when(users.findAccess(USER_ID)).thenReturn(Optional.of(new OwnerAccess(null, false, null, null)),
                    Optional.of(new OwnerAccess(null, false, null, null)), Optional.of(new OwnerAccess(null, true, null, null)),
                    Optional.of(new OwnerAccess("$h", true, null, null)));
            when(verifier.hasEnvironmentHash()).thenReturn(false, true, true, true);

            assertThat(useCase.handle().passwordSetupRequired()).isTrue();
            assertThat(useCase.handle().passwordSetupRequired()).isFalse();
            assertThat(useCase.handle().passwordSetupRequired()).isTrue();
            assertThat(useCase.handle().passwordSetupRequired()).isFalse();
        }
    }

    @Nested
    @DisplayName("EnsureOwner com redefinição")
    class Reset {

        private EnsureOwnerUseCaseImpl useCase() {
            return new EnsureOwnerUseCaseImpl(users, ids, refreshTokens, time);
        }

        @Test
        @DisplayName("e-mail novo + reset: renomeia mantendo o id, apaga a senha e revoga todas as sessões; sem inserir")
        void deveRenomearEResetar() {
            when(users.findOwner()).thenReturn(Optional.of(new User(USER_ID, "dono@moneylog.local")));
            when(users.findAccess(USER_ID)).thenReturn(Optional.of(new OwnerAccess(null, false, null, null)));

            OwnerEnsured result = useCase().handle(new EnsureOwnerCommand("V@mviana.tech", true));

            assertThat(result.owner()).isEqualTo(new User(USER_ID, "v@mviana.tech"));
            assertThat(result.passwordReset()).isTrue();
            verify(users).updateEmail(USER_ID, "v@mviana.tech");
            verify(users).resetPassword(USER_ID, NOW);
            verify(refreshTokens).revokeAllByUser(USER_ID, NOW);
            verify(users, never()).insert(any());
        }

        @Test
        @DisplayName("sem a variável de reset: a senha atual é mantida")
        void naoDeveResetarSemPedido() {
            when(users.findOwner()).thenReturn(Optional.of(new User(USER_ID, "dono@moneylog.local")));

            OwnerEnsured result = useCase().handle(new EnsureOwnerCommand("v@mviana.tech", false));

            assertThat(result.passwordReset()).isFalse();
            verify(users, never()).resetPassword(any(), any());
            verify(refreshTokens, never()).revokeAllByUser(any(), any());
        }

        @Test
        @DisplayName("reset repetido com o primeiro acesso já pendente: idempotente (reiniciar não revoga de novo)")
        void deveSerIdempotente() {
            when(users.findOwner()).thenReturn(Optional.of(new User(USER_ID, EMAIL)));
            when(users.findAccess(USER_ID)).thenReturn(Optional.of(new OwnerAccess(null, true, null, null)));

            assertThat(useCase().handle(new EnsureOwnerCommand(EMAIL, true)).passwordReset()).isFalse();
            verify(users, never()).resetPassword(any(), any());
        }
    }

    @Nested
    @DisplayName("Login: ordem das senhas")
    class Login {

        private LoginUseCaseImpl useCase() {
            SessionOpener sessions = new SessionOpener(tokenIssuer, refreshTokens, ids, Duration.ofDays(30));
            return new LoginUseCaseImpl(users, verifier, sessions, ids, time);
        }

        private void sessaoAberta() {
            when(ids.next()).thenReturn(FAMILY_ID, TOKEN_ID);
            when(tokenIssuer.newRefreshTokenValue()).thenReturn("refresh");
            when(tokenIssuer.issueAccessToken(USER_ID)).thenReturn(new AccessToken("jwt", NOW.plusSeconds(900)));
        }

        @Test
        @DisplayName("senha do banco definida: é a única que vale; o hash do ambiente nem é consultado")
        void bancoTemPrioridade() {
            when(users.findByEmail(EMAIL)).thenReturn(Optional.of(new User(USER_ID, EMAIL)));
            when(users.findAccess(USER_ID)).thenReturn(Optional.of(new OwnerAccess("$banco", false, null, null)));
            when(verifier.matches("senha-do-banco", "$banco")).thenReturn(true);
            sessaoAberta();

            assertThat(useCase().handle(new LoginCommand(EMAIL, "senha-do-banco")).accessToken().value()).isEqualTo("jwt");
            verify(verifier, never()).verifyOwner(anyString());
        }

        @Test
        @DisplayName("senha do ambiente com senha do banco definida: recusa")
        void ambienteNaoValeComSenhaNoBanco() {
            when(users.findByEmail(EMAIL)).thenReturn(Optional.of(new User(USER_ID, EMAIL)));
            when(users.findAccess(USER_ID)).thenReturn(Optional.of(new OwnerAccess("$banco", false, null, null)));
            when(verifier.matches("senha-do-ambiente", "$banco")).thenReturn(false);

            assertThatThrownBy(() -> useCase().handle(new LoginCommand(EMAIL, "senha-do-ambiente")))
                    .isInstanceOf(InvalidCredentialsException.class);
            verify(verifier, never()).verifyOwner(anyString());
        }

        @Test
        @DisplayName("sem senha no banco e sem redefinição: vale o hash do ambiente (retrocompatibilidade)")
        void retrocompatibilidade() {
            when(users.findByEmail(EMAIL)).thenReturn(Optional.of(new User(USER_ID, EMAIL)));
            when(users.findAccess(USER_ID)).thenReturn(Optional.of(new OwnerAccess(null, false, null, null)));
            when(verifier.verifyOwner("senha-do-ambiente")).thenReturn(true);
            sessaoAberta();

            assertThat(useCase().handle(new LoginCommand(EMAIL, "senha-do-ambiente")).accessToken().value()).isEqualTo("jwt");
        }

        @Test
        @DisplayName("redefinição pendente: nem o hash do ambiente entra")
        void pendenteBloqueiaAmbiente() {
            when(users.findByEmail(EMAIL)).thenReturn(Optional.of(new User(USER_ID, EMAIL)));
            when(users.findAccess(USER_ID)).thenReturn(Optional.of(new OwnerAccess(null, true, null, null)));

            assertThatThrownBy(() -> useCase().handle(new LoginCommand(EMAIL, "senha-do-ambiente")))
                    .isInstanceOf(InvalidCredentialsException.class);
            verify(verifier, never()).verifyOwner(anyString());
        }
    }
}
