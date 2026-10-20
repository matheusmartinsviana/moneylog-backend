package com.moneylog.core.invitation.app;

import java.time.Instant;
import java.util.UUID;

import com.moneylog.core.invitation.domain.DeliveryStatus;
import com.moneylog.core.invitation.domain.Invitation;
import com.moneylog.core.invitation.domain.InvitationStatus;

public record InvitationView(UUID id, String email, InvitationStatus status, Instant createdAt, Instant expiresAt, Instant acceptedAt,
                             Instant lastSentAt, DeliveryStatus deliveryStatus) {

    public static InvitationView of(Invitation invitation, Instant now) {
        return new InvitationView(invitation.id(), invitation.email(), invitation.statusAt(now), invitation.createdAt(),
                invitation.expiresAt(), invitation.acceptedAt(), invitation.lastSentAt(), invitation.deliveryStatus());
    }
}
