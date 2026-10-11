package com.moneylog.core.commitment.app.usecase;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.commitment.domain.CommitmentKind;
import com.moneylog.core.commitment.domain.ScheduleItemType;
import com.moneylog.core.sk.money.Money;

public interface CreateCommitmentUseCase {

    com.moneylog.core.commitment.app.CommitmentView handle(CreateCommitmentCommand command);

    record ItemInput(String label, ScheduleItemType type, LocalDate dueDate, Money amount, boolean paid, LocalDate paidOn) {
    }

    record CreateCommitmentCommand(UUID userId, CommitmentKind kind, String name, String counterparty, String notes, Money total,
                                   Integer installmentsTotal, Integer installmentsPaid, Money paidAmount, LocalDate nextDueDate,
                                   List<ItemInput> schedule, UUID idempotencyKey) {
    }

    String IDEMPOTENCY_SCOPE = "POST /v1/commitments";
}
