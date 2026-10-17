package com.moneylog.core.recurrence.app;

import com.moneylog.core.sk.money.Money;

public record SubscriptionView(RecurrenceView recurrence, Money monthlyEquivalent, Money monthlyEquivalentMine,
                               Money lastChargedAmount, boolean ended) {
}
