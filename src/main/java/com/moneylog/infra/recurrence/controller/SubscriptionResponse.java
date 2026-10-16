package com.moneylog.infra.recurrence.controller;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.recurrence.app.SubscriptionListing;
import com.moneylog.core.recurrence.app.SubscriptionView;
import com.moneylog.core.recurrence.domain.RecurrenceScope;
import com.moneylog.core.sk.money.Money;

public record SubscriptionResponse(RecurrenceResponse recurrence, Money monthlyEquivalent, Money monthlyEquivalentMine,
                                   Money lastChargedAmount, boolean ended) {

    public record ScopeTotalResponse(RecurrenceScope scope, Money monthly) {
    }

    public record OverlapItemResponse(UUID id, String description, Money monthlyEquivalent) {
    }

    public record OverlapResponse(String group, List<OverlapItemResponse> items, Money monthly) {
    }

    public record ListResponse(List<SubscriptionResponse> items, boolean hasNext, Money totalMonthly, Money totalMonthlyMine,
                               List<ScopeTotalResponse> byScope, List<OverlapResponse> overlaps) {

        public static ListResponse of(SubscriptionListing listing) {
            return new ListResponse(listing.items().items().stream().map(SubscriptionResponse::of).toList(),
                    listing.items().hasNext(), listing.totalMonthly(), listing.totalMonthlyMine(),
                    listing.byScope().stream().map(s -> new ScopeTotalResponse(s.scope(), s.monthly())).toList(),
                    listing.overlaps().stream().map(o -> new OverlapResponse(o.group(), o.items().stream()
                            .map(i -> new OverlapItemResponse(i.id(), i.description(), i.monthlyEquivalent())).toList(), o.monthly()))
                            .toList());
        }
    }

    public static SubscriptionResponse of(SubscriptionView view) {
        return new SubscriptionResponse(RecurrenceResponse.of(view.recurrence()), view.monthlyEquivalent(),
                view.monthlyEquivalentMine(), view.lastChargedAmount(), view.ended());
    }
}
