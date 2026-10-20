package com.moneylog.core.invitation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.invitation.exception.InvitationExceptions.InvalidInvitationException;
import com.moneylog.core.invitation.exception.InvitationExceptions.InvitationNotResendableException;
import com.moneylog.core.invitation.exception.InvitationExceptions.InvitationNotRevocableException;

@DisplayName("Invitation")
class InvitationTest {

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    private static final Instant EXPIRES = NOW.plus(Duration.ofDays(7));
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static Invitation pending() {
        return Invitation.issue(UUID.randomUUID(), "  Ana@Example.COM ", "hash-1", OWNER, NOW, EXPIRES);
    }

    private static Invitation accepted() {
        return pending().accept(USER, NOW.plusSeconds(60));
    }

    @Test
    @DisplayName("nasce PENDING, com e-mail normalizado e envio ainda não confirmado")
    void issuesPending() {
        Invitation invitation = pending();
        assertThat(invitation.status()).isEqualTo(InvitationStatus.PENDING);
        assertThat(invitation.email()).isEqualTo("ana@example.com");
        assertThat(invitation.deliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(invitation.expiresAt()).isEqualTo(EXPIRES);
    }

    @Test
    @DisplayName("PENDING vencido (expires_at <= agora) é lido como EXPIRED")
    void readsOverduePendingAsExpired() {
        Invitation invitation = pending();
        assertThat(invitation.statusAt(EXPIRES.minusMillis(1))).isEqualTo(InvitationStatus.PENDING);
        assertThat(invitation.statusAt(EXPIRES)).isEqualTo(InvitationStatus.EXPIRED);
        assertThat(invitation.statusAt(EXPIRES.plusSeconds(1))).isEqualTo(InvitationStatus.EXPIRED);
    }

    @Test
    @DisplayName("exige validade posterior à criação")
    void rejectsNonPositiveValidity() {
        assertThatThrownBy(() -> Invitation.issue(UUID.randomUUID(), "a@b.com", "h", OWNER, NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("reenviar PENDING mantém PENDING com novo hash e nova validade")
    void resendsPending() {
        Instant later = NOW.plus(Duration.ofDays(1));
        Invitation resent = pending().reissue("hash-2", later, later.plus(Duration.ofDays(7)));
        assertThat(resent.status()).isEqualTo(InvitationStatus.PENDING);
        assertThat(resent.tokenHash()).isEqualTo("hash-2");
        assertThat(resent.expiresAt()).isEqualTo(later.plus(Duration.ofDays(7)));
        assertThat(resent.createdAt()).isEqualTo(NOW);
        assertThat(resent.deliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
    }

    @Test
    @DisplayName("reenviar EXPIRED (gravado ou derivado) volta a PENDING")
    void resendsExpired() {
        Instant later = EXPIRES.plusSeconds(5);
        Invitation resent = pending().reissue("hash-2", later, later.plus(Duration.ofDays(7)));
        assertThat(resent.status()).isEqualTo(InvitationStatus.PENDING);
        Invitation stored = new Invitation(UUID.randomUUID(), "a@b.com", "h", InvitationStatus.EXPIRED, OWNER, NOW, EXPIRES, NOW,
                DeliveryStatus.SENT, null, null);
        assertThat(stored.reissue("h2", later, later.plus(Duration.ofDays(7))).status()).isEqualTo(InvitationStatus.PENDING);
    }

    @Test
    @DisplayName("reenviar ACCEPTED ou REVOKED é recusado")
    void refusesResendOfFinalStates() {
        assertThatThrownBy(() -> accepted().reissue("h", NOW, EXPIRES.plusSeconds(1))).isInstanceOf(InvitationNotResendableException.class);
        assertThatThrownBy(() -> pending().revoke().reissue("h", NOW, EXPIRES.plusSeconds(1)))
                .isInstanceOf(InvitationNotResendableException.class);
    }

    @Test
    @DisplayName("revogar PENDING e EXPIRED vira REVOKED; repetir é idempotente")
    void revokes() {
        Invitation revoked = pending().revoke();
        assertThat(revoked.status()).isEqualTo(InvitationStatus.REVOKED);
        assertThat(revoked.revoke()).isSameAs(revoked);
        Invitation expired = new Invitation(UUID.randomUUID(), "a@b.com", "h", InvitationStatus.EXPIRED, OWNER, NOW, EXPIRES, NOW,
                DeliveryStatus.SENT, null, null);
        assertThat(expired.revoke().status()).isEqualTo(InvitationStatus.REVOKED);
    }

    @Test
    @DisplayName("revogar ACCEPTED é recusado")
    void refusesRevokeOfAccepted() {
        assertThatThrownBy(() -> accepted().revoke()).isInstanceOf(InvitationNotRevocableException.class);
    }

    @Test
    @DisplayName("aceitar só vale em PENDING não vencido")
    void acceptsOnlyLivePending() {
        Invitation accepted = accepted();
        assertThat(accepted.status()).isEqualTo(InvitationStatus.ACCEPTED);
        assertThat(accepted.acceptedUserId()).isEqualTo(USER);
        assertThat(accepted.acceptedAt()).isEqualTo(NOW.plusSeconds(60));
        assertThatThrownBy(() -> accepted.accept(USER, NOW)).isInstanceOf(InvalidInvitationException.class);
        assertThatThrownBy(() -> pending().revoke().accept(USER, NOW)).isInstanceOf(InvalidInvitationException.class);
        assertThatThrownBy(() -> pending().accept(USER, EXPIRES)).isInstanceOf(InvalidInvitationException.class);
    }

    @Test
    @DisplayName("ACCEPTED sem usuário ou usuário em estado não aceito violam a invariante")
    void guardsAcceptedInvariant() {
        assertThatThrownBy(() -> new Invitation(UUID.randomUUID(), "a@b.com", "h", InvitationStatus.ACCEPTED, OWNER, NOW, EXPIRES, NOW,
                DeliveryStatus.SENT, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Invitation(UUID.randomUUID(), "a@b.com", "h", InvitationStatus.PENDING, OWNER, NOW, EXPIRES, NOW,
                DeliveryStatus.SENT, NOW, USER)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("entrega confirmada atualiza lastSentAt; falha mantém o último envio")
    void tracksDelivery() {
        Instant sentAt = NOW.plusSeconds(3);
        Invitation delivered = pending().delivered(sentAt);
        assertThat(delivered.deliveryStatus()).isEqualTo(DeliveryStatus.SENT);
        assertThat(delivered.lastSentAt()).isEqualTo(sentAt);
        Invitation failed = delivered.deliveryFailed();
        assertThat(failed.deliveryStatus()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(failed.lastSentAt()).isEqualTo(sentAt);
    }
}
