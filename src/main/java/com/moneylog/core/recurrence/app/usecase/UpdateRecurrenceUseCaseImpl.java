package com.moneylog.core.recurrence.app.usecase;

import java.util.Objects;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.recurrence.app.RecurrenceShares;
import com.moneylog.core.recurrence.app.RecurrenceView;
import com.moneylog.core.recurrence.app.RecurrenceViews;
import com.moneylog.core.recurrence.app.gateways.RecurrenceGateway;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.InvalidRecurrenceException;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.RecurrenceNotFoundException;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.provenance.Provenance;
import com.moneylog.core.sk.time.FinancialDates;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.domain.ForeignCharge;

public class UpdateRecurrenceUseCaseImpl implements UpdateRecurrenceUseCase {

    private final RecurrenceGateway recurrences;
    private final RecurrenceReferences references;
    private final RecurrenceViews views;
    private final TimeProvider time;
    private final PersonGateway persons;

    public UpdateRecurrenceUseCaseImpl(RecurrenceGateway recurrences, CategoryGateway categories, AccountGateway accounts,
                                       CardGateway cards, TimeProvider time) {
        this(recurrences, categories, accounts, cards, time, null);
    }

    public UpdateRecurrenceUseCaseImpl(RecurrenceGateway recurrences, CategoryGateway categories, AccountGateway accounts,
                                       CardGateway cards, TimeProvider time, PersonGateway persons) {
        this.persons = persons;
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
        ForeignCharge basis = c.foreign().toBasis();
        recurrence.updateDetails(basis == null ? c.amount() : basis.brl(), c.description(), c.categoryId(), c.accountId(), c.method(), c.cardId(),
                c.incomeCompetenceOffsetMonths() == null ? recurrence.incomeCompetenceOffsetMonths() : c.incomeCompetenceOffsetMonths(),
                c.nature());
        if (c.shares() != null) {
            RecurrenceShares.requireUsable(persons, c.userId(), c.shares());
            recurrence.changeShares(c.splitMode(), c.shares());
        }
        recurrence.defineForeignBasis(basis);
        recurrence.defineSubscriptionTraits(c.subscription().forUpdate(recurrence.traits()));
        recurrence.defineProvenance(Provenance.orCurrent(c.provenance(), recurrence.provenance()));
        recurrence.changeEnd(c.endDate());
        return views.one(c.userId(), recurrences.save(recurrence), time.today());
    }
}
