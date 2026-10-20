package com.moneylog.infra.invitation.controller;

import java.time.Instant;
import java.util.UUID;

import com.moneylog.core.invitation.app.InvitationView;
import com.moneylog.core.invitation.domain.DeliveryStatus;
import com.moneylog.core.invitation.domain.InvitationStatus;

public record InvitationResponse(UUID id, String email, InvitationStatus status, Instant createdAt, Instant expiresAt,
                                 Instant acceptedAt, Instant lastSentAt, DeliveryStatus deliveryStatus) {

    public static InvitationResponse of(InvitationView view) {
        return new InvitationResponse(view.id(), view.email(), view.status(), view.createdAt(), view.expiresAt(), view.acceptedAt(),
                view.lastSentAt(), view.deliveryStatus());
    }
}
