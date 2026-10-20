package com.moneylog.core.invitation.app.usecase;

import com.moneylog.core.invitation.app.InvitationPolicy;
import com.moneylog.core.invitation.app.gateways.InvitationGateway;
import com.moneylog.core.invitation.domain.Invitation;
import com.moneylog.core.invitation.exception.InvitationExceptions.InvalidInvitationException;
import com.moneylog.core.sk.id.SecureToken;
import com.moneylog.core.sk.time.TimeProvider;

public class PreviewInvitationUseCaseImpl implements PreviewInvitationUseCase {

    private final InvitationGateway invitations;
    private final TimeProvider time;
    private final InvitationPolicy policy;

    public PreviewInvitationUseCaseImpl(InvitationGateway invitations, TimeProvider time, InvitationPolicy policy) {
        this.invitations = invitations;
        this.time = time;
        this.policy = policy;
    }

    @Override
    public InvitationPreview handle(PreviewInvitationCommand command) {
        if (!policy.enabled() || command.token() == null) {
            throw new InvalidInvitationException();
        }
        Invitation invitation = invitations.findByTokenHash(SecureToken.hash(command.token()))
                .filter(found -> found.isPendingAt(time.now()))
                .orElseThrow(InvalidInvitationException::new);
        return new InvitationPreview(invitation.email(), invitation.expiresAt());
    }
}
