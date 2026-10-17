package com.moneylog.core.recurrence.app;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.recurrence.domain.RecurrenceScope;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.page.Slice;

public record SubscriptionListing(Slice<SubscriptionView> items, Money totalMonthly, Money totalMonthlyMine, List<ScopeTotal> byScope,
                                  List<Overlap> overlaps) {

    public SubscriptionListing {
        byScope = List.copyOf(byScope);
        overlaps = List.copyOf(overlaps);
    }

    public record ScopeTotal(RecurrenceScope scope, Money monthly) {
    }

    public record OverlapItem(UUID id, String description, Money monthlyEquivalent) {
    }

    public record Overlap(String group, List<OverlapItem> items, Money monthly) {

        public Overlap {
            items = List.copyOf(items);
        }
    }
}
