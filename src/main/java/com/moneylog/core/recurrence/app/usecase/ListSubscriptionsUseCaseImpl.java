package com.moneylog.core.recurrence.app.usecase;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.recurrence.app.RecurrenceView;
import com.moneylog.core.recurrence.app.RecurrenceViews;
import com.moneylog.core.recurrence.app.SubscriptionListing;
import com.moneylog.core.recurrence.app.SubscriptionListing.Overlap;
import com.moneylog.core.recurrence.app.SubscriptionListing.OverlapItem;
import com.moneylog.core.recurrence.app.SubscriptionListing.ScopeTotal;
import com.moneylog.core.recurrence.app.SubscriptionStatus;
import com.moneylog.core.recurrence.app.SubscriptionView;
import com.moneylog.core.recurrence.app.gateways.RecurrenceGateway;
import com.moneylog.core.recurrence.app.gateways.RecurrenceGateway.RealizedOccurrence;
import com.moneylog.core.recurrence.domain.RecurrenceScope;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.recurrence.domain.SubscriptionCost;
import com.moneylog.core.recurrence.domain.SubscriptionCost.Monthly;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.domain.TransactionType;

public class ListSubscriptionsUseCaseImpl implements ListSubscriptionsUseCase {

    private final RecurrenceGateway recurrences;
    private final RecurrenceViews views;
    private final TimeProvider time;

    public ListSubscriptionsUseCaseImpl(RecurrenceGateway recurrences, RecurrenceViews views, TimeProvider time) {
        this.recurrences = recurrences;
        this.views = views;
        this.time = time;
    }

    @Override
    public SubscriptionListing handle(ListSubscriptionsQuery query) {
        UUID userId = query.userId();
        LocalDate today = time.today();
        List<Versioned<RecurringTransaction>> subscriptions = recurrences.findAllVersioned(userId).stream()
                .filter(item -> item.value().type() != TransactionType.INCOME && item.value().isSubscription())
                .filter(item -> query.scope() == null || item.value().traits().scope() == query.scope())
                .sorted(Comparator.comparing((Versioned<RecurringTransaction> item) -> item.value().description(),
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)).thenComparing(item -> item.value().id()))
                .toList();
        List<Versioned<RecurringTransaction>> listed = subscriptions.stream()
                .filter(item -> matches(query.status(), ended(item.value(), today))).toList();

        Monthly total = SubscriptionCost.sum(listed.stream().map(item -> SubscriptionCost.full(item.value())).toList());
        Monthly mine = SubscriptionCost.sum(listed.stream().map(item -> SubscriptionCost.mine(item.value())).toList());
        List<ScopeTotal> byScope = new ArrayList<>();
        for (RecurrenceScope scope : RecurrenceScope.values()) {
            byScope.add(new ScopeTotal(scope, SubscriptionCost.sum(listed.stream().filter(item -> item.value().traits().scope() == scope)
                    .map(item -> SubscriptionCost.full(item.value())).toList()).money()));
        }
        return new SubscriptionListing(page(userId, listed, query.page(), today), total.money(), mine.money(), byScope,
                overlaps(subscriptions, today));
    }

    private Slice<SubscriptionView> page(UUID userId, List<Versioned<RecurringTransaction>> listed, PageQuery page, LocalDate today) {
        int from = (int) Math.min(page.offset(), listed.size());
        int to = Math.min(from + page.pageSize(), listed.size());
        List<Versioned<RecurringTransaction>> slice = listed.subList(from, to);
        List<RecurrenceView> recurrenceViews = views.of(userId, slice, today);
        Map<UUID, Map<LocalDate, RealizedOccurrence>> realized = recurrences.realizedOccurrences(userId,
                slice.stream().map(item -> item.value().id()).toList());
        List<SubscriptionView> items = new ArrayList<>();
        for (RecurrenceView view : recurrenceViews) {
            RecurringTransaction r = view.recurrence();
            items.add(new SubscriptionView(view, SubscriptionCost.full(r).money(), SubscriptionCost.mine(r).money(),
                    lastCharged(realized.get(r.id())), ended(r, today)));
        }
        return new Slice<>(items, to < listed.size());
    }

    private static com.moneylog.core.sk.money.Money lastCharged(Map<LocalDate, RealizedOccurrence> byDate) {
        if (byDate == null || byDate.isEmpty()) {
            return null;
        }
        return byDate.entrySet().stream().max(Map.Entry.comparingByKey()).map(entry -> entry.getValue().amount()).orElse(null);
    }

    private static List<Overlap> overlaps(List<Versioned<RecurringTransaction>> subscriptions, LocalDate today) {
        Map<String, List<RecurringTransaction>> groups = new LinkedHashMap<>();
        for (Versioned<RecurringTransaction> item : subscriptions) {
            RecurringTransaction r = item.value();
            String group = r.traits().overlapGroup();
            if (group != null && !ended(r, today)) {
                groups.computeIfAbsent(group.toLowerCase(Locale.ROOT), key -> new ArrayList<>()).add(r);
            }
        }
        List<Overlap> overlaps = new ArrayList<>();
        for (List<RecurringTransaction> members : groups.values()) {
            if (members.size() >= 2) {
                List<OverlapItem> items = members.stream()
                        .map(r -> new OverlapItem(r.id(), r.description(), SubscriptionCost.full(r).money())).toList();
                overlaps.add(new Overlap(members.get(0).traits().overlapGroup(), items,
                        SubscriptionCost.sum(members.stream().map(SubscriptionCost::full).toList()).money()));
            }
        }
        return overlaps;
    }

    private static boolean ended(RecurringTransaction recurrence, LocalDate today) {
        return !recurrence.isActive() || recurrence.isEnded(today);
    }

    private static boolean matches(SubscriptionStatus status, boolean ended) {
        return switch (status) {
            case ACTIVE -> !ended;
            case ENDED -> ended;
            case ALL -> true;
        };
    }
}
