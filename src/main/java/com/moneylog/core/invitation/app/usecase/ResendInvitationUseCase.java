package com.moneylog.core.invitation.app.usecase;

import java.util.UUID;

import com.moneylog.core.invitation.app.InvitationView;

public interface ResendInvitationUseCase {

    InvitationView handle(ResendInvitationCommand command);

    record ResendInvitationCommand(UUID inviterId, UUID invitationId) {
    }
}
