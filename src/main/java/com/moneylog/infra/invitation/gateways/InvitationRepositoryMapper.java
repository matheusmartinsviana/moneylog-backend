package com.moneylog.infra.invitation.gateways;

import com.moneylog.core.invitation.domain.Invitation;
import com.moneylog.infra.invitation.persistence.InvitationEntity;

final class InvitationRepositoryMapper {

    private InvitationRepositoryMapper() {
    }

    static Invitation toDomain(InvitationEntity e) {
        return new Invitation(e.getId(), e.getEmail(), e.getTokenHash(), e.getStatus(), e.getInvitedBy(), e.getCreatedAt(),
                e.getExpiresAt(), e.getLastSentAt(), e.getDeliveryStatus(), e.getAcceptedAt(), e.getAcceptedUserId());
    }

    static InvitationEntity toEntity(Invitation i) {
        return new InvitationEntity(i.id(), i.email(), i.tokenHash(), i.status(), i.invitedBy(), i.createdAt(), i.expiresAt(),
                i.lastSentAt(), i.deliveryStatus(), i.acceptedAt(), i.acceptedUserId());
    }
}
