package com.moneylog.core.invitation.app.usecase;

import java.time.Instant;

public interface PreviewInvitationUseCase {

    InvitationPreview handle(PreviewInvitationCommand command);

    record PreviewInvitationCommand(String token) {

        @Override
        public String toString() {
            return "PreviewInvitationCommand[]";
        }
    }

    record InvitationPreview(String email, Instant expiresAt) {
    }
}
