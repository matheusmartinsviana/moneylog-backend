package com.moneylog.core.invitation.app.usecase;

import java.time.Duration;

public interface PurgeInvitationsUseCase {

    PurgeResult handle(PurgeInvitationsCommand command);

    record PurgeInvitationsCommand(Duration retention) {
    }

    record PurgeResult(int expired, int deleted) {
    }
}
