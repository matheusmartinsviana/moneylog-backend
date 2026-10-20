package com.moneylog.core.invitation.app.usecase;

import java.util.List;

import com.moneylog.core.invitation.app.InvitationView;
import com.moneylog.core.invitation.domain.InvitationStatus;

public interface ListInvitationsUseCase {

    List<InvitationView> handle(ListInvitationsQuery query);

    record ListInvitationsQuery(InvitationStatus status) {
    }
}
