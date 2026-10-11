package com.moneylog.core.finance.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

public record SpendingByCategory(Competence competence, Money total, Money base, List<Item> items, Other other) {

    public record Item(UUID categoryId, Money amount, BigDecimal share) {
    }

    public record Other(Money amount, BigDecimal share, int categories) {
    }

    public static SpendingByCategory of(EntryReport consumption, int limit, Function<UUID, String> nameOf) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit precisa ser ao menos 1");
        }
        Comparator<Map.Entry<UUID, Money>> order = Comparator.<Map.Entry<UUID, Money>, Money>comparing(Map.Entry::getValue).reversed()
                .thenComparing(e -> String.valueOf(nameOf.apply(e.getKey())).toLowerCase(java.util.Locale.ROOT))
                .thenComparing(Map.Entry::getKey);
        List<Map.Entry<UUID, Money>> positives = consumption.byCategory().entrySet().stream()
                .filter(e -> e.getValue().isPositive()).sorted(order).toList();
        Money zero = Money.zero(CurrencyCode.BRL);
        Money base = positives.stream().map(Map.Entry::getValue).reduce(zero, Money::add);
        List<Item> items = new ArrayList<>();
        for (Map.Entry<UUID, Money> e : positives.subList(0, Math.min(limit, positives.size()))) {
            items.add(new Item(e.getKey(), e.getValue(), share(e.getValue(), base)));
        }
        List<Map.Entry<UUID, Money>> rest = positives.subList(items.size(), positives.size());
        Other other = null;
        if (!rest.isEmpty()) {
            Money amount = rest.stream().map(Map.Entry::getValue).reduce(zero, Money::add);
            other = new Other(amount, share(amount, base), rest.size());
        }
        return new SpendingByCategory(consumption.competence(), consumption.total(), base, List.copyOf(items), other);
    }

    private static BigDecimal share(Money amount, Money base) {
        return base.isPositive() ? MonthlySummary.ratio(amount, base) : null;
    }
}
