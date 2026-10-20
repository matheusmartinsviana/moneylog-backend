package com.moneylog.infra.invitation.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.moneylog.core.invitation.domain.DeliveryStatus;
import com.moneylog.core.invitation.domain.InvitationStatus;

@Entity
@Table(name = "user_invitations")
public class InvitationEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private InvitationStatus status;

    @Column(name = "invited_by", nullable = false)
    private UUID invitedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "last_sent_at", nullable = false)
    private Instant lastSentAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status", nullable = false, length = 16)
    private DeliveryStatus deliveryStatus;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(name = "accepted_user_id")
    private UUID acceptedUserId;

    protected InvitationEntity() {
    }

    public InvitationEntity(UUID id, String email, String tokenHash, InvitationStatus status, UUID invitedBy, Instant createdAt,
                            Instant expiresAt, Instant lastSentAt, DeliveryStatus deliveryStatus, Instant acceptedAt,
                            UUID acceptedUserId) {
        this.id = id;
        this.email = email;
        this.invitedBy = invitedBy;
        this.createdAt = createdAt;
        apply(tokenHash, status, expiresAt, lastSentAt, deliveryStatus, acceptedAt, acceptedUserId, createdAt);
    }

    public void apply(String newTokenHash, InvitationStatus newStatus, Instant newExpiresAt, Instant newLastSentAt,
                      DeliveryStatus newDeliveryStatus, Instant newAcceptedAt, UUID newAcceptedUserId, Instant now) {
        this.tokenHash = newTokenHash;
        this.status = newStatus;
        this.expiresAt = newExpiresAt;
        this.lastSentAt = newLastSentAt;
        this.deliveryStatus = newDeliveryStatus;
        this.acceptedAt = newAcceptedAt;
        this.acceptedUserId = newAcceptedUserId;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public InvitationStatus getStatus() {
        return status;
    }

    public UUID getInvitedBy() {
        return invitedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getLastSentAt() {
        return lastSentAt;
    }

    public DeliveryStatus getDeliveryStatus() {
        return deliveryStatus;
    }

    public Instant getAcceptedAt() {
        return acceptedAt;
    }

    public UUID getAcceptedUserId() {
        return acceptedUserId;
    }
}
