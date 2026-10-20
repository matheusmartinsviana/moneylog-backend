package com.moneylog.core.invitation.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.domain.User;
import com.moneylog.core.invitation.app.gateways.InvitationGateway;
import com.moneylog.core.invitation.domain.DeliveryStatus;
import com.moneylog.core.invitation.domain.Invitation;
import com.moneylog.core.invitation.domain.InvitationStatus;
import com.moneylog.core.invitation.exception.InvitationExceptions.EmailAlreadyRegisteredException;
import com.moneylog.core.invitation.exception.InvitationExceptions.InvitationNotFoundException;
import com.moneylog.core.invitation.exception.InvitationExceptions.InvitationNotResendableException;
import com.moneylog.core.invitation.exception.InvitationExceptions.InvitationPendingException;
import com.moneylog.core.invitation.exception.InvitationExceptions.InvitationsDisabledException;
import com.moneylog.core.invitation.exception.InvitationExceptions.UserLimitReachedException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.id.SecureToken;
import com.moneylog.core.sk.time.ClockTimeProvider;
import com.moneylog.core.sk.time.TimeProvider;

@ExtendWith(MockitoExtension.class)
@DisplayName("InvitationIssuer")
class InvitationIssuerImplTest {

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID NEW_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final User OWNER = User.owner(OWNER_ID, "dono@example.com");

    @Mock
    private InvitationGateway invitations;
    @Mock
    private UserGateway users;
    @Mock
    private IdGenerator ids;

    private final TimeProvider time = new ClockTimeProvider(Clock.fixed(NOW, ZoneOffset.UTC));
    private InvitationIssuerImpl issuer;

    @BeforeEach
    void setUp() {
        issuer = new InvitationIssuerImpl(invitations, users, ids, time, new InvitationPolicy(true, 7, 3));
    }

    private static Invitation stored(InvitationStatus status, Instant expiresAt) {
        boolean accepted = status == InvitationStatus.ACCEPTED;
        return new Invitation(UUID.randomUUID(), "ana@example.com", "old-hash", status, OWNER_ID, NOW.minus(Duration.ofDays(10)),
                expiresAt, NOW.minus(Duration.ofDays(10)), DeliveryStatus.SENT, accepted ? NOW.minusSeconds(5) : null,
                accepted ? UUID.randomUUID() : null);
    }

    private void inviterExists() {
        when(users.findById(OWNER_ID)).thenReturn(Optional.of(OWNER));
    }

    @Test
    @DisplayName("convida e-mail novo: expira vencidos, confere regras, grava só o hash e expira em agora + 7 dias")
    void invitesNewEmail() {
        inviterExists();
        when(ids.next()).thenReturn(NEW_ID);
        when(users.existsByEmail("ana@example.com")).thenReturn(false);
        when(invitations.findPendingByEmail("ana@example.com")).thenReturn(Optional.empty());
        when(users.count()).thenReturn(1L);
        when(invitations.countPendingNotExpired(NOW)).thenReturn(0L);

        IssuedInvitation issued = issuer.issue(OWNER_ID, " Ana@Example.com ");

        InOrder order = inOrder(invitations, users);
        order.verify(invitations).lockIssuance();
        order.verify(invitations).expireOverdueOf("ana@example.com", NOW);
        order.verify(users).existsByEmail("ana@example.com");
        ArgumentCaptor<Invitation> saved = ArgumentCaptor.forClass(Invitation.class);
        verify(invitations).insert(saved.capture());
        assertThat(saved.getValue().tokenHash()).isEqualTo(SecureToken.hash(issued.token())).isNotEqualTo(issued.token());
        assertThat(saved.getValue().expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        assertThat(saved.getValue().status()).isEqualTo(InvitationStatus.PENDING);
        assertThat(issued.inviterEmail()).isEqualTo("dono@example.com");
        assertThat(issued.toString()).doesNotContain(issued.token());
    }

    @Test
    @DisplayName("e-mail que já é usuário: 409 e nada é gravado")
    void refusesRegisteredEmail() {
        inviterExists();
        when(users.existsByEmail("ana@example.com")).thenReturn(true);
        assertThatThrownBy(() -> issuer.issue(OWNER_ID, "ana@example.com")).isInstanceOf(EmailAlreadyRegisteredException.class);
        verify(invitations, never()).insert(any());
    }

    @Test
    @DisplayName("convite pendente não expirado: 409 com o id do existente")
    void refusesPendingDuplicate() {
        inviterExists();
        Invitation existing = stored(InvitationStatus.PENDING, NOW.plusSeconds(60));
        when(invitations.findPendingByEmail("ana@example.com")).thenReturn(Optional.of(existing));
        assertThatThrownBy(() -> issuer.issue(OWNER_ID, "ana@example.com")).isInstanceOfSatisfying(InvitationPendingException.class,
                e -> assertThat(e.invitationId()).isEqualTo(existing.id()));
        verify(invitations, never()).insert(any());
    }

    @Test
    @DisplayName("teto atingido (usuários + pendentes não expirados): 409")
    void refusesWhenLimitReached() {
        inviterExists();
        when(invitations.findPendingByEmail(anyString())).thenReturn(Optional.empty());
        when(users.count()).thenReturn(2L);
        when(invitations.countPendingNotExpired(NOW)).thenReturn(1L);
        assertThatThrownBy(() -> issuer.issue(OWNER_ID, "ana@example.com")).isInstanceOf(UserLimitReachedException.class);
        verify(invitations, never()).insert(any());
    }

    @Test
    @DisplayName("flag desligada: 404 INVITATIONS_DISABLED antes de qualquer consulta")
    void failsWhenDisabled() {
        InvitationIssuerImpl disabled = new InvitationIssuerImpl(invitations, users, ids, time, new InvitationPolicy(false, 7, 3));
        assertThatThrownBy(() -> disabled.issue(OWNER_ID, "ana@example.com")).isInstanceOf(InvitationsDisabledException.class);
        assertThatThrownBy(() -> disabled.reissue(OWNER_ID, UUID.randomUUID())).isInstanceOf(InvitationsDisabledException.class);
        org.mockito.Mockito.verifyNoInteractions(invitations, users);
    }

    @Test
    @DisplayName("reenviar PENDING: novo token e nova validade, sem reavaliar e-mail nem teto")
    void resendsPending() {
        inviterExists();
        Invitation pending = stored(InvitationStatus.PENDING, NOW.plusSeconds(3600));
        when(invitations.findByIdForUpdate(pending.id())).thenReturn(Optional.of(pending));

        IssuedInvitation issued = issuer.reissue(OWNER_ID, pending.id());

        assertThat(issued.invitation().tokenHash()).isEqualTo(SecureToken.hash(issued.token())).isNotEqualTo("old-hash");
        assertThat(issued.invitation().expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
        verify(users, never()).existsByEmail(anyString());
        verify(users, never()).count();
        verify(invitations).update(issued.invitation());
    }

    @Test
    @DisplayName("reenviar EXPIRED: reavalia e-mail, pendente e teto, e volta a PENDING")
    void resendsExpired() {
        inviterExists();
        Invitation expired = stored(InvitationStatus.EXPIRED, NOW.minusSeconds(1));
        when(invitations.findByIdForUpdate(expired.id())).thenReturn(Optional.of(expired));
        when(users.existsByEmail("ana@example.com")).thenReturn(false);
        when(invitations.findPendingByEmail("ana@example.com")).thenReturn(Optional.empty());
        when(users.count()).thenReturn(1L);
        when(invitations.countPendingNotExpired(NOW)).thenReturn(0L);

        IssuedInvitation issued = issuer.reissue(OWNER_ID, expired.id());

        assertThat(issued.invitation().status()).isEqualTo(InvitationStatus.PENDING);
        verify(invitations).update(issued.invitation());
    }

    @Test
    @DisplayName("reenviar EXPIRED com outro PENDING do e-mail: 409 com o id do pendente, sem gravar")
    void resendExpiredConflictsWithPending() {
        inviterExists();
        Invitation expired = stored(InvitationStatus.EXPIRED, NOW.minusSeconds(1));
        Invitation other = stored(InvitationStatus.PENDING, NOW.plusSeconds(60));
        when(invitations.findByIdForUpdate(expired.id())).thenReturn(Optional.of(expired));
        when(invitations.findPendingByEmail("ana@example.com")).thenReturn(Optional.of(other));
        assertThatThrownBy(() -> issuer.reissue(OWNER_ID, expired.id())).isInstanceOfSatisfying(InvitationPendingException.class,
                e -> assertThat(e.invitationId()).isEqualTo(other.id()));
        verify(invitations, never()).update(any());
    }

    @Test
    @DisplayName("reenviar EXPIRED de e-mail que virou usuário: 409; no teto: 409")
    void resendExpiredRechecksEmailAndLimit() {
        inviterExists();
        Invitation expired = stored(InvitationStatus.EXPIRED, NOW.minusSeconds(1));
        when(invitations.findByIdForUpdate(expired.id())).thenReturn(Optional.of(expired));
        when(users.existsByEmail("ana@example.com")).thenReturn(true);
        assertThatThrownBy(() -> issuer.reissue(OWNER_ID, expired.id())).isInstanceOf(EmailAlreadyRegisteredException.class);

        when(users.existsByEmail("ana@example.com")).thenReturn(false);
        when(invitations.findPendingByEmail("ana@example.com")).thenReturn(Optional.empty());
        when(users.count()).thenReturn(3L);
        when(invitations.countPendingNotExpired(NOW)).thenReturn(0L);
        assertThatThrownBy(() -> issuer.reissue(OWNER_ID, expired.id())).isInstanceOf(UserLimitReachedException.class);
        verify(invitations, never()).update(any());
    }

    @Test
    @DisplayName("PENDING vencido é tratado como EXPIRED no reenvio")
    void resendTreatsOverduePendingAsExpired() {
        inviterExists();
        Invitation overdue = stored(InvitationStatus.PENDING, NOW.minusSeconds(1));
        when(invitations.findByIdForUpdate(overdue.id())).thenReturn(Optional.of(overdue));
        when(users.existsByEmail("ana@example.com")).thenReturn(true);
        assertThatThrownBy(() -> issuer.reissue(OWNER_ID, overdue.id())).isInstanceOf(EmailAlreadyRegisteredException.class);
    }

    @Test
    @DisplayName("reenviar ACCEPTED ou REVOKED: 409 sem reavaliar regras; id desconhecido: 404")
    void resendRefusesFinalStates() {
        inviterExists();
        Invitation accepted = stored(InvitationStatus.ACCEPTED, NOW.plusSeconds(60));
        Invitation revoked = stored(InvitationStatus.REVOKED, NOW.plusSeconds(60));
        when(invitations.findByIdForUpdate(accepted.id())).thenReturn(Optional.of(accepted));
        when(invitations.findByIdForUpdate(revoked.id())).thenReturn(Optional.of(revoked));
        assertThatThrownBy(() -> issuer.reissue(OWNER_ID, accepted.id())).isInstanceOf(InvitationNotResendableException.class);
        assertThatThrownBy(() -> issuer.reissue(OWNER_ID, revoked.id())).isInstanceOf(InvitationNotResendableException.class);
        UUID unknown = UUID.randomUUID();
        when(invitations.findByIdForUpdate(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> issuer.reissue(OWNER_ID, unknown)).isInstanceOf(InvitationNotFoundException.class);
        verify(users, never()).existsByEmail(anyString());
    }

    @Test
    @DisplayName("registra a entrega com lock: SENT atualiza lastSentAt, FAILED mantém")
    void recordsDelivery() {
        Invitation pending = stored(InvitationStatus.PENDING, NOW.plusSeconds(60));
        when(invitations.findByIdForUpdate(pending.id())).thenReturn(Optional.of(pending));
        Invitation sent = issuer.recordDelivery(pending.id(), "old-hash", true, NOW);
        assertThat(sent.deliveryStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(sent.lastSentAt()).isEqualTo(NOW);
        Invitation failed = issuer.recordDelivery(pending.id(), "old-hash", false, NOW);
        assertThat(failed.deliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
        verify(invitations).update(sent);
        verify(invitations).update(failed);
    }

    @Test
    @DisplayName("entrega de token já substituído não altera o convite")
    void ignoresDeliveryOfSupersededToken() {
        Invitation pending = stored(InvitationStatus.PENDING, NOW.plusSeconds(60));
        when(invitations.findByIdForUpdate(pending.id())).thenReturn(Optional.of(pending));
        Invitation result = issuer.recordDelivery(pending.id(), "outro-hash", true, NOW);
        assertThat(result).isSameAs(pending);
        verify(invitations, never()).update(any());
    }
}
