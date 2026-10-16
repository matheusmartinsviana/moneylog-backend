package com.moneylog.core.finance.app.recurrences.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.finance.app.transactions.TransactionDetails;
import com.moneylog.core.recurrence.app.ForeignInput;
import com.moneylog.core.sk.money.Money;

public interface RealizeCardRecurrenceUseCase {

    TransactionDetails handle(RealizeCardRecurrenceCommand command);

    record RealizeCardRecurrenceCommand(UUID userId, UUID recurrenceId, LocalDate occurrenceDate, Money actualAmount,
                                        UUID transactionId, ForeignInput foreign) {

        public RealizeCardRecurrenceCommand {
            foreign = foreign == null ? ForeignInput.NONE : foreign;
        }

        public RealizeCardRecurrenceCommand(UUID userId, UUID recurrenceId, LocalDate occurrenceDate, Money actualAmount,
                                            UUID transactionId) {
            this(userId, recurrenceId, occurrenceDate, actualAmount, transactionId, null);
        }

        public RealizeCardRecurrenceCommand(UUID userId, UUID recurrenceId, LocalDate occurrenceDate, Money actualAmount) {
            this(userId, recurrenceId, occurrenceDate, actualAmount, null);
        }
    }
}
