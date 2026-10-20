package com.moneylog.core.invitation.app;

import java.time.Instant;
import java.util.UUID;

import com.moneylog.core.invitation.domain.Invitation;

public interface InvitationIssuer {

    IssuedInvitation issue(UUID inviterId, String email);

    IssuedInvitation reissue(UUID inviterId, UUID invitationId);

    Invitation recordDelivery(UUID invitationId, String tokenHash, boolean delivered, Instant at);
}
