package com.moneylog.core.invitation.app.usecase;

import java.time.Instant;

import com.moneylog.core.invitation.app.gateways.InvitationGateway;
import com.moneylog.core.sk.time.TimeProvider;

public class PurgeInvitationsUseCaseImpl implements PurgeInvitationsUseCase {

    private final InvitationGateway invitations;
    private final TimeProvider time;

    public PurgeInvitationsUseCaseImpl(InvitationGateway invitations, TimeProvider time) {
        this.invitations = invitations;
        this.time = time;
    }

    @Override
    public PurgeResult handle(PurgeInvitationsCommand command) {
        Instant now = time.now();
        int expired = invitations.expireOverdue(now);
        int deleted = invitations.deleteFinalBefore(now.minus(command.retention()));
        return new PurgeResult(expired, deleted);
    }
}
