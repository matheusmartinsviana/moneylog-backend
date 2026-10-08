package com.moneylog.core.commitment.app.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.commitment.app.CommitmentView;
import com.moneylog.core.sk.money.Money;

public interface UpdateCommitmentProgressUseCase {

    /** Atualiza o que o usuário informou de um empréstimo de terceiro (parcelas pagas, valor pago, próximo vencimento, observações). */
    CommitmentView handle(UpdateCommitmentProgressCommand command);

    record UpdateCommitmentProgressCommand(UUID userId, UUID id, int installmentsPaid, Money paidAmount, LocalDate nextDueDate,
                                           String notes, long version, String name, String counterparty) {

        public UpdateCommitmentProgressCommand(UUID userId, UUID id, int installmentsPaid, Money paidAmount, LocalDate nextDueDate,
                                               String notes, long version) {
            this(userId, id, installmentsPaid, paidAmount, nextDueDate, notes, version, null, null);
        }
    }
}
