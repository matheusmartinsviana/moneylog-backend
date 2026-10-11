package com.moneylog.infra.recurrence.controller;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.recurrence.app.OccurrenceView;
import com.moneylog.core.recurrence.domain.OccurrenceStatus;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.sk.money.FlowDirection;
import com.moneylog.core.sk.money.Money;
import com.moneylog.infra.recurrence.controller.RecurrenceRequests.RecurrenceType;

public record OccurrenceResponse(UUID recurrenceId, LocalDate date, OccurrenceStatus status, boolean overdue, RecurrenceType type,
                                 FlowDirection direction, Money amount, String description, UUID categoryId, UUID accountId,
                                 UUID cardId, UUID transactionId) {

    public static OccurrenceResponse of(OccurrenceView v) {
        RecurringTransaction r = v.recurrence();
        return new OccurrenceResponse(r.id(), v.date(), v.status(), v.overdue(), RecurrenceType.valueOf(r.type().name()), r.direction(),
                v.amount(), r.description(), r.categoryId(), r.accountId(), r.cardId(), v.transactionId());
    }

    public record Slice(java.util.List<OccurrenceResponse> items, boolean hasNext, boolean overdueTruncated) {

        public static Slice of(com.moneylog.core.recurrence.app.OccurrenceListing listing) {
            return new Slice(listing.items().stream().map(OccurrenceResponse::of).toList(), listing.hasNext(), listing.overdueTruncated());
        }
    }
}
