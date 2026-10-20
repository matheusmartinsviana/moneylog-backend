package com.moneylog.core.invitation.app.usecase;

import java.time.Instant;

import com.moneylog.core.invitation.app.InvitationView;
import com.moneylog.core.invitation.app.gateways.InvitationGateway;
import com.moneylog.core.invitation.domain.Invitation;
import com.moneylog.core.invitation.exception.InvitationExceptions.InvitationNotFoundException;
import com.moneylog.core.sk.time.TimeProvider;

public class RevokeInvitationUseCaseImpl implements RevokeInvitationUseCase {

    private final InvitationGateway invitations;
    private final TimeProvider time;

    public RevokeInvitationUseCaseImpl(InvitationGateway invitations, TimeProvider time) {
        this.invitations = invitations;
        this.time = time;
    }

    @Override
    public InvitationView handle(RevokeInvitationCommand command) {
        Invitation current = invitations.findByIdForUpdate(command.invitationId()).orElseThrow(InvitationNotFoundException::new);
        Invitation revoked = current.revoke();
        if (revoked != current) {
            invitations.update(revoked);
        }
        Instant now = time.now();
        return InvitationView.of(revoked, now);
    }
}
