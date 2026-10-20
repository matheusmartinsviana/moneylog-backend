package com.moneylog.core.invitation.app.usecase;

import java.time.Instant;
import java.util.List;

import com.moneylog.core.invitation.app.InvitationView;
import com.moneylog.core.invitation.app.gateways.InvitationGateway;
import com.moneylog.core.sk.time.TimeProvider;

public class ListInvitationsUseCaseImpl implements ListInvitationsUseCase {

    private final InvitationGateway invitations;
    private final TimeProvider time;

    public ListInvitationsUseCaseImpl(InvitationGateway invitations, TimeProvider time) {
        this.invitations = invitations;
        this.time = time;
    }

    @Override
    public List<InvitationView> handle(ListInvitationsQuery query) {
        Instant now = time.now();
        return invitations.findAllNewestFirst().stream()
                .map(invitation -> InvitationView.of(invitation, now))
                .filter(view -> query.status() == null || view.status() == query.status())
                .toList();
    }
}
