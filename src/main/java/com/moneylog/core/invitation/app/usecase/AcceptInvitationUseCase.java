package com.moneylog.core.invitation.app.usecase;

import com.moneylog.core.auth.app.AuthSession;

public interface AcceptInvitationUseCase {

    AuthSession handle(AcceptInvitationCommand command);

    record AcceptInvitationCommand(String token, String password) {

        @Override
        public String toString() {
            return "AcceptInvitationCommand[]";
        }
    }
}
