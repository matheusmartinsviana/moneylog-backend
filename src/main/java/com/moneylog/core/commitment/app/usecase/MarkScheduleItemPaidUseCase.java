package com.moneylog.core.commitment.app.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.commitment.app.CommitmentView;

public interface MarkScheduleItemPaidUseCase {

    CommitmentView handle(MarkScheduleItemPaidCommand command);

    record MarkScheduleItemPaidCommand(UUID userId, UUID id, int position, LocalDate paidOn, long version) {
    }
}
