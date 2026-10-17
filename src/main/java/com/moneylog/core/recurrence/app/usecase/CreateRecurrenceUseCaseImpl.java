package com.moneylog.core.recurrence.app.usecase;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.recurrence.app.RecurrenceShares;
import com.moneylog.core.recurrence.app.SubscriptionInput;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.ForeignCharge;
import com.moneylog.core.recurrence.app.RecurrenceView;
import com.moneylog.core.recurrence.app.RecurrenceViews;
import com.moneylog.core.recurrence.app.gateways.RecurrenceGateway;
import com.moneylog.core.recurrence.domain.Frequency;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.recurrence.domain.Schedule;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.InvalidRecurrenceException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.FinancialDates;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.RecurrenceNotFoundException;
import com.moneylog.core.transaction.app.IdempotentClaim;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway.Claim;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.core.transaction.exception.TransactionExceptions.SharesInvalidException;

public class CreateRecurrenceUseCaseImpl implements CreateRecurrenceUseCase {

    private final RecurrenceGateway recurrences;
    private final RecurrenceReferences references;
    private final RecurrenceViews views;
    private final IdempotencyGateway idempotency;
    private final IdGenerator ids;
    private final TimeProvider time;
    private final PersonGateway persons;

    @SuppressWarnings("java:S107")
    public CreateRecurrenceUseCaseImpl(RecurrenceGateway recurrences, CategoryGateway categories, AccountGateway accounts,
                                       CardGateway cards, IdempotencyGateway idempotency, IdGenerator ids, TimeProvider time) {
        this(recurrences, categories, accounts, cards, idempotency, ids, time, null);
    }

    @SuppressWarnings("java:S107")
    public CreateRecurrenceUseCaseImpl(RecurrenceGateway recurrences, CategoryGateway categories, AccountGateway accounts,
                                       CardGateway cards, IdempotencyGateway idempotency, IdGenerator ids, TimeProvider time,
                                       PersonGateway persons) {
        this.persons = persons;
        this.idempotency = idempotency;
        this.recurrences = recurrences;
        this.references = new RecurrenceReferences(categories, accounts, cards);
        this.views = new RecurrenceViews(categories, accounts, cards);
        this.ids = ids;
        this.time = time;
    }

    @Override
    public RecurrenceView handle(CreateRecurrenceCommand c) {
        UUID recurrenceId = ids.next();
        if (c.idempotencyKey() != null) {
            Claim existing = IdempotentClaim.claimOrFind(idempotency, c.userId(), IDEMPOTENCY_SCOPE, c.idempotencyKey(), hashOf(c), recurrenceId,
                    time.now());
            if (existing != null) {
                IdempotentClaim.requireSameCommand(existing, hashOf(c));
                return views.one(c.userId(), recurrences.findVersioned(c.userId(), existing.resourceId())
                        .orElseThrow(() -> new RecurrenceNotFoundException(existing.resourceId())), time.today());
            }
        }
        if (c.accountId() != null && c.cardId() != null) {
            throw new InvalidRecurrenceException("informe a conta ou o cartão, não os dois.");
        }
        FinancialDates.requireInWindow(c.startDate(), "startDate");
        FinancialDates.requireInWindow(c.endDate(), "endDate");
        if (c.frequency() == Frequency.CUSTOM && c.interval() == null) {
            throw new InvalidRecurrenceException("a frequência personalizada exige intervalo ≥ 1 e unidade.");
        }
        Schedule schedule = new Schedule(c.frequency(), c.interval() == null ? 1 : c.interval(), c.unit(), c.startDate(), c.endDate(),
                c.maxOccurrences());
        Category category = references.category(c.userId(), c.categoryId(), c.type());
        UUID categoryId = category.id();
        ForeignCharge basis = c.foreign().toBasis();
        Money amount = basis == null ? c.amount() : basis.brl();
        RecurringTransaction recurrence;
        if (c.type() == TransactionType.CARD_PURCHASE) {
            if (c.accountId() != null || c.method() != null) {
                throw new InvalidRecurrenceException("compra no cartão exige só o cartão.");
            }
            RecurrenceShares.requireUsable(persons, c.userId(), c.shares());
            recurrence = RecurringTransaction.ofCard(recurrenceId, c.userId(), amount, c.description(), categoryId, c.cardId(),
                    schedule, c.splitMode(), c.shares() == null ? List.of() : c.shares());
        } else {
            if (c.cardId() != null) {
                throw new InvalidRecurrenceException("despesa e receita exigem conta e meio de pagamento, não cartão.");
            }
            if (c.shares() != null && !c.shares().isEmpty()) {
                throw new SharesInvalidException("o rateio existe só na recorrência de cartão.");
            }
            if (c.accountId() == null && c.method() != null) {
                throw new InvalidRecurrenceException("o meio de pagamento exige a conta (ou deixe os dois para definir ao confirmar).");
            }
            recurrence = RecurringTransaction.ofAccount(recurrenceId, c.userId(), c.type(), amount, c.description(), categoryId,
                    c.accountId(), c.accountId() == null ? null : (c.method() == null ? PaymentMethod.OTHER : c.method()), schedule,
                    c.incomeCompetenceOffsetMonths() == null ? 0 : c.incomeCompetenceOffsetMonths(), c.nature());
        }
        recurrence.defineForeignBasis(basis);
        recurrence.defineSubscriptionTraits(c.subscription().forCreation());
        recurrence.defineProvenance(c.provenance());
        if (recurrence.accountId() != null) {
            references.account(c.userId(), recurrence.accountId(), c.type());
        } else if (recurrence.cardId() != null) {
            references.card(c.userId(), recurrence.cardId());
        }
        Versioned<RecurringTransaction> saved = recurrences.save(recurrence);
        return views.one(c.userId(), saved, time.today());
    }

    private static String hashOf(CreateRecurrenceCommand c) {
        return IdempotentClaim.hash("type=" + c.type() + ";amount=" + c.amount().toPlainString() + ";description=" + nz(c.description()).strip()
                + ";categoryId=" + nz(c.categoryId()) + ";accountId=" + nz(c.accountId()) + ";method=" + nz(c.method()) + ";cardId=" + nz(c.cardId())
                + ";frequency=" + c.frequency() + ";interval=" + nz(c.interval()) + ";unit=" + nz(c.unit()) + ";startDate=" + nz(c.startDate())
                + ";endDate=" + nz(c.endDate()) + ";max=" + nz(c.maxOccurrences()) + ";"
                + (c.incomeCompetenceOffsetMonths() == null || c.incomeCompetenceOffsetMonths() == 0 ? ""
                        : "incomeCompetenceOffsetMonths=" + c.incomeCompetenceOffsetMonths() + ";")
                + (c.nature() == null ? "" : "nature=" + c.nature() + ";") + c.foreign().canonical()
                + (c.subscription() == SubscriptionInput.NONE ? "" : c.subscription().canonical())
                + (c.provenance() == null ? "" : "provenance=" + c.provenance().origin() + "/" + c.provenance().confidence() + ";")
                + (c.shares() == null || c.shares().isEmpty() ? "" : "split=" + c.splitMode() + ":" + c.shares().stream()
                        .map(s -> nz(s.personId()) + "/" + nz(s.bps()) + "/" + (s.amount() == null ? "-" : s.amount().toPlainString()))
                        .collect(java.util.stream.Collectors.joining(",")) + ";"));
    }

    private static String nz(Object value) {
        return value == null ? "-" : value.toString();
    }
}
