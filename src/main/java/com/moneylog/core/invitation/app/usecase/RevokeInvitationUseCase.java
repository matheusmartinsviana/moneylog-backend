package com.moneylog.core.invitation.app.usecase;

import java.util.UUID;

import com.moneylog.core.invitation.app.InvitationView;

public interface RevokeInvitationUseCase {

    InvitationView handle(RevokeInvitationCommand command);

    record RevokeInvitationCommand(UUID invitationId) {
    }
}
