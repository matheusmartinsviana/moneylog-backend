package com.moneylog.core.invitation.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.auth.domain.User;
import com.moneylog.core.invitation.exception.InvitationExceptions.InvalidInvitationException;
import com.moneylog.core.invitation.exception.InvitationExceptions.InvitationNotResendableException;
import com.moneylog.core.invitation.exception.InvitationExceptions.InvitationNotRevocableException;

public final class Invitation {

    private final UUID id;
    private final String email;
    private final String tokenHash;
    private final InvitationStatus status;
    private final UUID invitedBy;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final Instant lastSentAt;
    private final DeliveryStatus deliveryStatus;
    private final Instant acceptedAt;
    private final UUID acceptedUserId;

    public Invitation(UUID id, String email, String tokenHash, InvitationStatus status, UUID invitedBy, Instant createdAt,
                      Instant expiresAt, Instant lastSentAt, DeliveryStatus deliveryStatus, Instant acceptedAt, UUID acceptedUserId) {
        this.id = Objects.requireNonNull(id, "id");
        this.email = User.normalize(email);
        this.tokenHash = requireText(tokenHash, "tokenHash");
        this.status = Objects.requireNonNull(status, "status");
        this.invitedBy = Objects.requireNonNull(invitedBy, "invitedBy");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        this.lastSentAt = Objects.requireNonNull(lastSentAt, "lastSentAt");
        this.deliveryStatus = Objects.requireNonNull(deliveryStatus, "deliveryStatus");
        this.acceptedAt = acceptedAt;
        this.acceptedUserId = acceptedUserId;
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("a validade do convite deve ser posterior à criação");
        }
        if ((status == InvitationStatus.ACCEPTED) != (acceptedAt != null && acceptedUserId != null)) {
            throw new IllegalArgumentException("um convite aceito exige data e usuário do aceite, e só ele os tem");
        }
    }

    public static Invitation issue(UUID id, String email, String tokenHash, UUID invitedBy, Instant now, Instant expiresAt) {
        return new Invitation(id, email, tokenHash, InvitationStatus.PENDING, invitedBy, now, expiresAt, now, DeliveryStatus.FAILED,
                null, null);
    }

    public InvitationStatus statusAt(Instant now) {
        return status == InvitationStatus.PENDING && !expiresAt.isAfter(now) ? InvitationStatus.EXPIRED : status;
    }

    public boolean isPendingAt(Instant now) {
        return statusAt(now) == InvitationStatus.PENDING;
    }

    public boolean isResendableAt(Instant now) {
        InvitationStatus effective = statusAt(now);
        return effective == InvitationStatus.PENDING || effective == InvitationStatus.EXPIRED;
    }

    public Invitation reissue(String newTokenHash, Instant now, Instant newExpiresAt) {
        if (!isResendableAt(now)) {
            throw new InvitationNotResendableException();
        }
        return new Invitation(id, email, newTokenHash, InvitationStatus.PENDING, invitedBy, createdAt, newExpiresAt, now,
                DeliveryStatus.FAILED, null, null);
    }

    public Invitation revoke() {
        if (status == InvitationStatus.ACCEPTED) {
            throw new InvitationNotRevocableException();
        }
        if (status == InvitationStatus.REVOKED) {
            return this;
        }
        return new Invitation(id, email, tokenHash, InvitationStatus.REVOKED, invitedBy, createdAt, expiresAt, lastSentAt,
                deliveryStatus, null, null);
    }

    public Invitation accept(UUID userId, Instant now) {
        if (!isPendingAt(now)) {
            throw new InvalidInvitationException();
        }
        return new Invitation(id, email, tokenHash, InvitationStatus.ACCEPTED, invitedBy, createdAt, expiresAt, lastSentAt,
                deliveryStatus, now, Objects.requireNonNull(userId, "userId"));
    }

    public Invitation delivered(Instant at) {
        return new Invitation(id, email, tokenHash, status, invitedBy, createdAt, expiresAt, at, DeliveryStatus.SENT, acceptedAt,
                acceptedUserId);
    }

    public Invitation deliveryFailed() {
        return new Invitation(id, email, tokenHash, status, invitedBy, createdAt, expiresAt, lastSentAt, DeliveryStatus.FAILED,
                acceptedAt, acceptedUserId);
    }

    public UUID id() {
        return id;
    }

    public String email() {
        return email;
    }

    public String tokenHash() {
        return tokenHash;
    }

    public InvitationStatus status() {
        return status;
    }

    public UUID invitedBy() {
        return invitedBy;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Instant lastSentAt() {
        return lastSentAt;
    }

    public DeliveryStatus deliveryStatus() {
        return deliveryStatus;
    }

    public Instant acceptedAt() {
        return acceptedAt;
    }

    public UUID acceptedUserId() {
        return acceptedUserId;
    }

    @Override
    public String toString() {
        return "Invitation[id=" + id + ", status=" + status + "]";
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " é obrigatório");
        }
        return value;
    }
}
