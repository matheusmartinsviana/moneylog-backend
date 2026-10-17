package com.moneylog.core.finance.app.debts.usecase;

import java.util.UUID;

import com.moneylog.core.finance.domain.CardShareReceivables;
import com.moneylog.core.sk.time.Competence;

public interface SetReceivableExpectedMonthUseCase {

    Result handle(SetReceivableExpectedMonthCommand command);

    record SetReceivableExpectedMonthCommand(UUID userId, UUID transactionId, UUID installmentId, UUID personId, Competence month,
                                             Long version) {
    }

    record Result(UUID transactionId, UUID personId, CardShareReceivables.Part installment, long version) {
    }
}
