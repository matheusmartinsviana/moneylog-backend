package com.moneylog.core.recurrence.app;

import java.time.LocalDate;

import com.moneylog.core.recurrence.domain.RecurrenceScope;
import com.moneylog.core.recurrence.domain.SubscriptionTraits;

public record SubscriptionInput(Boolean subscription, RecurrenceScope scope, String channel, String overlapGroup, LocalDate lastUsedOn) {

    public static final SubscriptionInput NONE = new SubscriptionInput(null, null, null, null, null);

    public SubscriptionTraits forCreation() {
        return new SubscriptionTraits(Boolean.TRUE.equals(subscription), scope, channel, overlapGroup, lastUsedOn);
    }

    public SubscriptionTraits forUpdate(SubscriptionTraits current) {
        return new SubscriptionTraits(subscription == null ? current.subscription() : subscription,
                scope == null ? current.scope() : scope, channel, overlapGroup, lastUsedOn);
    }

    public String canonical() {
        return "sub=" + subscription + "/" + scope + "/" + channel + "/" + overlapGroup + "/" + lastUsedOn + ";";
    }
}
