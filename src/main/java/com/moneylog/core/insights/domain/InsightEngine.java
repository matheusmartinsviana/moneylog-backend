package com.moneylog.core.insights.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

public final class InsightEngine {

    public static final int MIN_DELTA_PERCENT = 10;
    public static final Money MIN_DELTA_AMOUNT = Money.ofCents(5_000, CurrencyCode.BRL);
    public static final String CATEGORY_DELTA = "CATEGORY_DELTA";
    public static final String CARD_COMMITTED = "CARD_COMMITTED";

    private InsightEngine() {
    }

    public static List<Insight> compute(Map<UUID, Money> current, Map<UUID, Money> previous, Money cardOutstanding,
                                        java.util.function.Function<UUID, String> categoryName) {
        List<Insight> insights = new ArrayList<>();
        current.entrySet().stream()
                .sorted(Comparator.<Map.Entry<UUID, Money>, Money>comparing(Map.Entry::getValue).reversed().thenComparing(Map.Entry::getKey))
                .forEach(e -> categoryDelta(e.getKey(), e.getValue(), previous.get(e.getKey()), categoryName).ifPresent(insights::add));
        if (cardOutstanding != null && cardOutstanding.isPositive()) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("amount", cardOutstanding.toPlainString());
            insights.add(new Insight(CARD_COMMITTED, "insight.card.committed", params, List.of("cardOutstanding")));
        }
        return List.copyOf(insights);
    }

    private static java.util.Optional<Insight> categoryDelta(UUID categoryId, Money now, Money before, java.util.function.Function<UUID, String> name) {
        if (before == null || !before.isPositive() || !now.isPositive()) {
            return java.util.Optional.empty();
        }
        Money delta = now.subtract(before);
        BigDecimal percent = BigDecimal.valueOf(delta.cents()).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(before.cents()), 1, RoundingMode.HALF_UP);
        BigDecimal rawPercent = BigDecimal.valueOf(delta.cents()).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(before.cents()), 6, RoundingMode.HALF_UP);
        if (rawPercent.abs().compareTo(BigDecimal.valueOf(MIN_DELTA_PERCENT)) < 0 || delta.abs().isLessThan(MIN_DELTA_AMOUNT)) {
            return java.util.Optional.empty();
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("categoryId", categoryId.toString());
        params.put("categoryName", String.valueOf(name.apply(categoryId)));
        params.put("current", now.toPlainString());
        params.put("previous", before.toPlainString());
        params.put("deltaAmount", delta.toPlainString());
        params.put("deltaPercent", percent.toPlainString());
        return java.util.Optional.of(new Insight(CATEGORY_DELTA, delta.isPositive() ? "insight.category.up" : "insight.category.down", params,
                List.of("category." + categoryId)));
    }
}
