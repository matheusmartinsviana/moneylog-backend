package com.moneylog.core.recurrence.app.usecase;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.recurrence.app.ForeignInput;
import com.moneylog.core.recurrence.app.RecurrenceView;
import com.moneylog.core.recurrence.app.SubscriptionInput;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.provenance.Provenance;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.ShareLine;
import com.moneylog.core.transaction.domain.SplitMode;
import com.moneylog.core.transaction.domain.TransactionNature;

public interface UpdateRecurrenceUseCase {

    RecurrenceView handle(UpdateRecurrenceCommand command);

    record UpdateRecurrenceCommand(UUID userId, UUID recurrenceId, Money amount, String description, UUID categoryId, UUID accountId,
                                   PaymentMethod method, UUID cardId, LocalDate endDate, long version,
                                   Integer incomeCompetenceOffsetMonths, TransactionNature nature, SplitMode splitMode,
                                   List<ShareLine> shares, ForeignInput foreign, SubscriptionInput subscription,
                                   Provenance provenance) {

        public UpdateRecurrenceCommand {
            foreign = foreign == null ? ForeignInput.NONE : foreign;
            subscription = subscription == null ? SubscriptionInput.NONE : subscription;
        }

        public UpdateRecurrenceCommand(UUID userId, UUID recurrenceId, Money amount, String description, UUID categoryId, UUID accountId,
                                       PaymentMethod method, UUID cardId, LocalDate endDate, long version,
                                       Integer incomeCompetenceOffsetMonths, TransactionNature nature, SplitMode splitMode,
                                       List<ShareLine> shares) {
            this(userId, recurrenceId, amount, description, categoryId, accountId, method, cardId, endDate, version,
                    incomeCompetenceOffsetMonths, nature, splitMode, shares, null, null, null);
        }

        public UpdateRecurrenceCommand(UUID userId, UUID recurrenceId, Money amount, String description, UUID categoryId, UUID accountId,
                                       PaymentMethod method, UUID cardId, LocalDate endDate, long version,
                                       Integer incomeCompetenceOffsetMonths, TransactionNature nature) {
            this(userId, recurrenceId, amount, description, categoryId, accountId, method, cardId, endDate, version,
                    incomeCompetenceOffsetMonths, nature, null, null);
        }

        public UpdateRecurrenceCommand(UUID userId, UUID recurrenceId, Money amount, String description, UUID categoryId, UUID accountId,
                                       PaymentMethod method, UUID cardId, LocalDate endDate, long version,
                                       Integer incomeCompetenceOffsetMonths) {
            this(userId, recurrenceId, amount, description, categoryId, accountId, method, cardId, endDate, version,
                    incomeCompetenceOffsetMonths, null);
        }

        public UpdateRecurrenceCommand(UUID userId, UUID recurrenceId, Money amount, String description, UUID categoryId, UUID accountId,
                                       PaymentMethod method, UUID cardId, LocalDate endDate, long version) {
            this(userId, recurrenceId, amount, description, categoryId, accountId, method, cardId, endDate, version, null, null);
        }
    }
}
