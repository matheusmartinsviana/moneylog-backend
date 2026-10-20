package com.moneylog.core.invitation.app.usecase;

import com.moneylog.core.invitation.app.InvitationDispatcher;
import com.moneylog.core.invitation.app.InvitationIssuer;
import com.moneylog.core.invitation.app.InvitationView;

public class InviteUserUseCaseImpl implements InviteUserUseCase {

    private final InvitationIssuer issuer;
    private final InvitationDispatcher dispatcher;

    public InviteUserUseCaseImpl(InvitationIssuer issuer, InvitationDispatcher dispatcher) {
        this.issuer = issuer;
        this.dispatcher = dispatcher;
    }

    @Override
    public InvitationView handle(InviteUserCommand command) {
        return dispatcher.deliver(issuer.issue(command.inviterId(), command.email()));
    }
}
