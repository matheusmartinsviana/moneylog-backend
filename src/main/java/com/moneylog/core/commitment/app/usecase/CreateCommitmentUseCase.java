package com.moneylog.core.commitment.app.usecase;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.commitment.domain.CommitmentClauses;
import com.moneylog.core.commitment.domain.CommitmentKind;
import com.moneylog.core.commitment.domain.ItemShare;
import com.moneylog.core.commitment.domain.ScheduleItemType;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.provenance.Provenance;

public interface CreateCommitmentUseCase {

    com.moneylog.core.commitment.app.CommitmentView handle(CreateCommitmentCommand command);

    record ItemInput(String label, ScheduleItemType type, LocalDate dueDate, Money amount, boolean paid, LocalDate paidOn,
                     List<ItemShare> shares, UUID coBuyerPersonId, Provenance provenance) {

        public ItemInput(String label, ScheduleItemType type, LocalDate dueDate, Money amount, boolean paid, LocalDate paidOn) {
            this(label, type, dueDate, amount, paid, paidOn, List.of(), null, null);
        }

        public ItemInput(String label, ScheduleItemType type, LocalDate dueDate, Money amount, boolean paid, LocalDate paidOn,
                         List<ItemShare> shares, UUID coBuyerPersonId) {
            this(label, type, dueDate, amount, paid, paidOn, shares, coBuyerPersonId, null);
        }
    }

    record CreateCommitmentCommand(UUID userId, CommitmentKind kind, String name, String counterparty, String notes, Money total,
                                   Integer installmentsTotal, Integer installmentsPaid, Money paidAmount, LocalDate nextDueDate,
                                   List<ItemInput> schedule, UUID idempotencyKey, CommitmentClauses clauses) {

        public CreateCommitmentCommand(UUID userId, CommitmentKind kind, String name, String counterparty, String notes, Money total,
                                       Integer installmentsTotal, Integer installmentsPaid, Money paidAmount, LocalDate nextDueDate,
                                       List<ItemInput> schedule, UUID idempotencyKey) {
            this(userId, kind, name, counterparty, notes, total, installmentsTotal, installmentsPaid, paidAmount, nextDueDate, schedule,
                    idempotencyKey, null);
        }
    }

    String IDEMPOTENCY_SCOPE = "POST /v1/commitments";
}
