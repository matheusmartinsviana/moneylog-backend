package com.moneylog.core.invitation.app.usecase;

import com.moneylog.core.invitation.app.InvitationDispatcher;
import com.moneylog.core.invitation.app.InvitationIssuer;
import com.moneylog.core.invitation.app.InvitationView;

public class ResendInvitationUseCaseImpl implements ResendInvitationUseCase {

    private final InvitationIssuer issuer;
    private final InvitationDispatcher dispatcher;

    public ResendInvitationUseCaseImpl(InvitationIssuer issuer, InvitationDispatcher dispatcher) {
        this.issuer = issuer;
        this.dispatcher = dispatcher;
    }

    @Override
    public InvitationView handle(ResendInvitationCommand command) {
        return dispatcher.deliver(issuer.reissue(command.inviterId(), command.invitationId()));
    }
}
