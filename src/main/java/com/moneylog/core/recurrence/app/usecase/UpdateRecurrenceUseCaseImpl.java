package com.moneylog.core.recurrence.app.usecase;

import java.util.Objects;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.recurrence.app.RecurrenceView;
import com.moneylog.core.recurrence.app.RecurrenceViews;
import com.moneylog.core.recurrence.app.gateways.RecurrenceGateway;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.InvalidRecurrenceException;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.RecurrenceNotFoundException;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.FinancialDates;
import com.moneylog.core.sk.time.TimeProvider;

public class UpdateRecurrenceUseCaseImpl implements UpdateRecurrenceUseCase {

    private final RecurrenceGateway recurrences;
    private final RecurrenceReferences references;
    private final RecurrenceViews views;
    private final TimeProvider time;

    public UpdateRecurrenceUseCaseImpl(RecurrenceGateway recurrences, CategoryGateway categories, AccountGateway accounts,
                                       CardGateway cards, TimeProvider time) {
        this.recurrences = recurrences;
        this.references = new RecurrenceReferences(categories, accounts, cards);
        this.views = new RecurrenceViews(categories, accounts, cards);
        this.time = time;
    }

    @Override
    public RecurrenceView handle(UpdateRecurrenceCommand c) {
        recurrences.lockForUpdate(c.userId(), c.recurrenceId());
        Versioned<RecurringTransaction> current = recurrences.findVersioned(c.userId(), c.recurrenceId())
                .orElseThrow(() -> new RecurrenceNotFoundException(c.recurrenceId()));
        if (current.version() != c.version()) {
            throw new VersionConflictException(c.version(), current.version());
        }
        RecurringTransaction recurrence = current.value();
        if (c.accountId() != null && c.cardId() != null) {
            throw new InvalidRecurrenceException("informe a conta ou o cartão, não os dois.");
        }
        FinancialDates.requireInWindow(c.endDate(), "endDate");
        // só o que mudou é revalidado: uma conta que arquivou depois não impede de corrigir o valor
        if (!Objects.equals(c.categoryId(), recurrence.categoryId())) {
            references.category(c.userId(), c.categoryId(), recurrence.type());
        }
        if (c.accountId() != null && !c.accountId().equals(recurrence.accountId())) {
            references.account(c.userId(), c.accountId(), recurrence.type());
        }
        if (c.cardId() != null && !c.cardId().equals(recurrence.cardId())) {
            references.card(c.userId(), c.cardId());
        }
        if (c.endDate() == null) {
            recurrence.ensureEndRemovalDoesNotReopen(time.today());
        }
        recurrence.updateDetails(c.amount(), c.description(), c.categoryId(), c.accountId(), c.method(), c.cardId());
        recurrence.changeEnd(c.endDate());
        return views.one(c.userId(), recurrences.save(recurrence), time.today());
    }
}
