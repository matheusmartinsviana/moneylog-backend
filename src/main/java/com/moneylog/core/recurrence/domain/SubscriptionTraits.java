package com.moneylog.core.recurrence.domain;

import java.time.LocalDate;

import com.moneylog.core.recurrence.exception.RecurrenceExceptions.InvalidRecurrenceException;
import com.moneylog.core.sk.time.FinancialDates;

public record SubscriptionTraits(boolean subscription, RecurrenceScope scope, String channel, String overlapGroup,
                                 LocalDate lastUsedOn) {

    public static final int TEXT_MAX_LENGTH = 60;

    public static final SubscriptionTraits NONE = new SubscriptionTraits(false, RecurrenceScope.PERSONAL, null, null, null);

    public SubscriptionTraits {
        scope = scope == null ? RecurrenceScope.PERSONAL : scope;
        channel = text(channel, "channel");
        overlapGroup = text(overlapGroup, "overlapGroup");
        if (lastUsedOn != null) {
            FinancialDates.requireInWindow(lastUsedOn, "lastUsedOn");
        }
    }

    private static String text(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.length() > TEXT_MAX_LENGTH) {
            throw new InvalidRecurrenceException(field + " pode ter no máximo " + TEXT_MAX_LENGTH + " caracteres.");
        }
        return trimmed;
    }
}
