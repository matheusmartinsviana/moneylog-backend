package com.moneylog.core.invitation.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.moneylog.core.auth.app.AuthSession;
import com.moneylog.core.auth.app.SessionOpener;
import com.moneylog.core.auth.app.UserCreated;
import com.moneylog.core.auth.app.gateways.PasswordVerifier;
import com.moneylog.core.auth.app.gateways.RefreshTokenGateway;
import com.moneylog.core.auth.app.gateways.TokenIssuer;
import com.moneylog.core.auth.app.gateways.TokenIssuer.AccessToken;
import com.moneylog.core.auth.app.gateways.UserCreatedPublisher;
import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.domain.User;
import com.moneylog.core.auth.domain.UserRole;
import com.moneylog.core.auth.exception.AuthExceptions.WeakPasswordException;
import com.moneylog.core.invitation.app.gateways.InvitationGateway;
import com.moneylog.core.invitation.app.usecase.AcceptInvitationUseCase.AcceptInvitationCommand;
import com.moneylog.core.invitation.app.usecase.AcceptInvitationUseCaseImpl;
import com.moneylog.core.invitation.domain.Invitation;
import com.moneylog.core.invitation.exception.InvitationExceptions.InvalidInvitationException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.id.SecureToken;
import com.moneylog.core.sk.time.ClockTimeProvider;

@ExtendWith(MockitoExtension.class)
@DisplayName("AcceptInvitationUseCase")
class AcceptInvitationUseCaseImplTest {

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID FAMILY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID TOKEN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final String PASSWORD = "senha-forte-do-convidado";

    @Mock
    private InvitationGateway invitations;
    @Mock
    private UserGateway users;
    @Mock
    private PasswordVerifier passwordVerifier;
    @Mock
    private TokenIssuer tokenIssuer;
    @Mock
    private RefreshTokenGateway refreshTokens;
    @Mock
    private UserCreatedPublisher userCreated;
    @Mock
    private IdGenerator ids;

    private AcceptInvitationUseCaseImpl useCase;
    private String token;
    private Invitation pending;

    @BeforeEach
    void setUp() {
        ClockTimeProvider time = new ClockTimeProvider(Clock.fixed(NOW, ZoneOffset.UTC));
        SessionOpener sessions = new SessionOpener(tokenIssuer, refreshTokens, ids, Duration.ofDays(30));
        useCase = new AcceptInvitationUseCaseImpl(invitations, users, passwordVerifier, sessions, userCreated, ids, time,
                new InvitationPolicy(true, 7, 10));
        token = SecureToken.generate();
        pending = Invitation.issue(UUID.randomUUID(), "ana@example.com", SecureToken.hash(token), OWNER_ID, NOW.minusSeconds(60),
                NOW.plus(Duration.ofDays(7)));
    }

    @Test
    @DisplayName("aceite válido: bloqueia o convite, cria MEMBER com a senha, consome, semeia (evento) e abre a sessão, nesta ordem")
    void acceptsValidInvitation() {
        when(invitations.findByTokenHash(SecureToken.hash(token))).thenReturn(Optional.of(pending));
        when(invitations.findByTokenHashForUpdate(SecureToken.hash(token))).thenReturn(Optional.of(pending));
        when(users.existsByEmail("ana@example.com")).thenReturn(false);
        when(passwordVerifier.encode(PASSWORD)).thenReturn("bcrypt-hash");
        when(ids.next()).thenReturn(USER_ID, FAMILY_ID, TOKEN_ID);
        when(invitations.markAccepted(any())).thenReturn(true);
        when(tokenIssuer.newRefreshTokenValue()).thenReturn("refresh");
        when(tokenIssuer.issueAccessToken(any())).thenReturn(new AccessToken("jwt", NOW.plusSeconds(900)));

        AuthSession session = useCase.handle(new AcceptInvitationCommand(token, PASSWORD));

        InOrder order = inOrder(invitations, users, userCreated, refreshTokens);
        order.verify(invitations).findByTokenHashForUpdate(SecureToken.hash(token));
        order.verify(users).insertWithPassword(User.member(USER_ID, "ana@example.com"), "bcrypt-hash", NOW);
        ArgumentCaptor<Invitation> accepted = ArgumentCaptor.forClass(Invitation.class);
        order.verify(invitations).markAccepted(accepted.capture());
        order.verify(userCreated).publish(new UserCreated(USER_ID, "ana@example.com"));
        order.verify(users).markLogin(USER_ID, NOW);
        order.verify(refreshTokens).insert(any());
        assertThat(accepted.getValue().acceptedUserId()).isEqualTo(USER_ID);
        assertThat(session.user().role()).isEqualTo(UserRole.MEMBER);
        assertThat(session.user().isActive()).isTrue();
        assertThat(session.accessToken().value()).isEqualTo("jwt");
    }

    @Test
    @DisplayName("token inexistente, vencido ou revogado: 404 sem tocar em usuários")
    void rejectsInvalidTokens() {
        when(invitations.findByTokenHash(anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> useCase.handle(new AcceptInvitationCommand(token, PASSWORD))).isInstanceOf(InvalidInvitationException.class);

        Invitation overdue = Invitation.issue(UUID.randomUUID(), "ana@example.com", SecureToken.hash(token), OWNER_ID,
                NOW.minus(Duration.ofDays(8)), NOW.minusSeconds(1));
        when(invitations.findByTokenHash(SecureToken.hash(token))).thenReturn(Optional.of(overdue));
        assertThatThrownBy(() -> useCase.handle(new AcceptInvitationCommand(token, PASSWORD))).isInstanceOf(InvalidInvitationException.class);

        when(invitations.findByTokenHash(SecureToken.hash(token))).thenReturn(Optional.of(pending.revoke()));
        assertThatThrownBy(() -> useCase.handle(new AcceptInvitationCommand(token, PASSWORD))).isInstanceOf(InvalidInvitationException.class);
        verifyNoInteractions(users, passwordVerifier, userCreated);
    }

    @Test
    @DisplayName("senha fraca: 422 sem consumir o token nem criar usuário")
    void weakPasswordKeepsTokenAlive() {
        when(invitations.findByTokenHash(SecureToken.hash(token))).thenReturn(Optional.of(pending));
        assertThatThrownBy(() -> useCase.handle(new AcceptInvitationCommand(token, "curta"))).isInstanceOf(WeakPasswordException.class);
        assertThatThrownBy(() -> useCase.handle(new AcceptInvitationCommand(token, "ana@example.com"))).isInstanceOf(WeakPasswordException.class);
        verify(invitations, never()).markAccepted(any());
        verify(users, never()).insertWithPassword(any(), anyString(), any());
        verify(invitations, never()).findByTokenHashForUpdate(anyString());
    }

    @Test
    @DisplayName("senha fraca com token inválido responde 404, sem revelar a validade do token")
    void weakPasswordWithInvalidTokenIsStill404() {
        when(invitations.findByTokenHash(anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> useCase.handle(new AcceptInvitationCommand(token, "curta"))).isInstanceOf(InvalidInvitationException.class);
    }

    @Test
    @DisplayName("e-mail que virou usuário: revoga em transação independente e responde 404")
    void revokesWhenEmailBecameUser() {
        when(invitations.findByTokenHash(SecureToken.hash(token))).thenReturn(Optional.of(pending));
        when(users.existsByEmail("ana@example.com")).thenReturn(true);

        assertThatThrownBy(() -> useCase.handle(new AcceptInvitationCommand(token, PASSWORD))).isInstanceOf(InvalidInvitationException.class);

        verify(invitations).revokeInIndependentTransaction(pending.id());
        verify(users, never()).insertWithPassword(any(), anyString(), any());
        verify(invitations, never()).markAccepted(any());
    }

    @Test
    @DisplayName("perdeu a corrida pelo consumo: 404 e nenhuma sessão")
    void losesRaceOnConsume() {
        when(invitations.findByTokenHash(SecureToken.hash(token))).thenReturn(Optional.of(pending));
        when(invitations.findByTokenHashForUpdate(SecureToken.hash(token))).thenReturn(Optional.of(pending));
        when(passwordVerifier.encode(PASSWORD)).thenReturn("bcrypt-hash");
        when(ids.next()).thenReturn(USER_ID);
        when(invitations.markAccepted(any())).thenReturn(false);

        assertThatThrownBy(() -> useCase.handle(new AcceptInvitationCommand(token, PASSWORD))).isInstanceOf(InvalidInvitationException.class);
        verifyNoInteractions(userCreated, refreshTokens);
    }

    @Test
    @DisplayName("já aceito entre a leitura e o bloqueio: 404 sem criar usuário")
    void alreadyAcceptedAfterLock() {
        when(invitations.findByTokenHash(SecureToken.hash(token))).thenReturn(Optional.of(pending));
        when(invitations.findByTokenHashForUpdate(SecureToken.hash(token))).thenReturn(Optional.of(pending.accept(USER_ID, NOW)));
        when(passwordVerifier.encode(PASSWORD)).thenReturn("bcrypt-hash");

        assertThatThrownBy(() -> useCase.handle(new AcceptInvitationCommand(token, PASSWORD))).isInstanceOf(InvalidInvitationException.class);
        verify(users, never()).insertWithPassword(any(), anyString(), any());
    }

    @Test
    @DisplayName("flag desligada: 404 INVALID_INVITATION sem consultar nada")
    void disabled() {
        AcceptInvitationUseCaseImpl disabled = new AcceptInvitationUseCaseImpl(invitations, users, passwordVerifier,
                new SessionOpener(tokenIssuer, refreshTokens, ids, Duration.ofDays(30)), userCreated, ids,
                new ClockTimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)), new InvitationPolicy(false, 7, 10));
        assertThatThrownBy(() -> disabled.handle(new AcceptInvitationCommand(token, PASSWORD))).isInstanceOf(InvalidInvitationException.class);
        verifyNoInteractions(invitations, users);
    }

    @Test
    @DisplayName("o comando não imprime token nem senha")
    void commandHidesSecrets() {
        assertThat(new AcceptInvitationCommand(token, PASSWORD).toString()).doesNotContain(token).doesNotContain(PASSWORD);
    }
}
