package com.moneylog.infra.recurrence.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.recurrence.app.RecurrenceView;
import com.moneylog.core.recurrence.domain.Frequency;
import com.moneylog.core.recurrence.domain.IntervalUnit;
import com.moneylog.core.recurrence.domain.RecurrenceScope;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.recurrence.domain.Schedule;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.provenance.Provenance;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.SplitMode;
import com.moneylog.infra.transaction.controller.PersonShareLineResponse;
import com.moneylog.core.transaction.domain.TransactionNature;
import com.moneylog.infra.recurrence.controller.RecurrenceRequests.RecurrenceType;

public record RecurrenceResponse(UUID id, RecurrenceType type, Money amount, String description, Ref category, Ref account, Ref card,
                                 PaymentMethod method, Frequency frequency, int interval, IntervalUnit unit, LocalDate startDate,
                                 LocalDate endDate, Integer maxOccurrences, boolean active, LocalDate nextOccurrence,
                                 Money monthlyEstimate, boolean ended, int incomeCompetenceOffsetMonths, long version, Instant createdAt,
                                 Instant updatedAt, TransactionNature nature, SplitMode splitMode,
                                 List<PersonShareLineResponse> shares, Money originalAmount, String referenceFxRate, Integer iofBps,
                                 @JsonProperty("isSubscription") boolean isSubscription, RecurrenceScope scope, String channel, String overlapGroup,
                                 LocalDate lastUsedOn, LocalDate activeUntil, Provenance provenance) {

    public record Ref(UUID id, String name) {
    }

    public static RecurrenceResponse of(RecurrenceView view) {
        RecurringTransaction r = view.recurrence();
        Schedule s = r.schedule();
        Account account = view.account();
        CreditCard card = view.card();
        return new RecurrenceResponse(r.id(), RecurrenceType.valueOf(r.type().name()), r.amount(), r.description(),
                new Ref(view.category().id(), view.category().name()), account == null ? null : new Ref(account.id(), account.name()),
                card == null ? null : new Ref(card.id(), card.name()), r.paymentMethod(), s.frequency(), s.interval(), s.unit(),
                s.startDate(), s.endDate(), s.maxOccurrences(), r.isActive(), view.nextOccurrence(), view.monthlyEstimate(), view.ended(),
                r.incomeCompetenceOffsetMonths(), view.stored().version(), view.createdAt(), view.updatedAt(), r.nature(),
                r.purchaseShares() == null ? null : r.purchaseShares().mode(),
                r.purchaseShares() == null ? List.of() : r.purchaseShares().lines().stream().map(PersonShareLineResponse::of).toList(),
                r.foreignBasis() == null ? null : r.foreignBasis().original(),
                r.foreignBasis() == null ? null : r.foreignBasis().fxRate().toPlainString(),
                r.foreignBasis() == null ? null : r.foreignBasis().iofBps(), r.traits().subscription(), r.traits().scope(),
                r.traits().channel(), r.traits().overlapGroup(), r.traits().lastUsedOn(), s.endDate(), r.provenance());
    }
}
