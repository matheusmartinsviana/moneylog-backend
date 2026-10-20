package com.moneylog.core.invitation.app.usecase;

import java.util.UUID;

import com.moneylog.core.invitation.app.InvitationView;

public interface InviteUserUseCase {

    InvitationView handle(InviteUserCommand command);

    record InviteUserCommand(UUID inviterId, String email) {
    }
}
