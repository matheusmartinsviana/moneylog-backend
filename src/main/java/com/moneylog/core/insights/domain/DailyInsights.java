package com.moneylog.core.insights.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.moneylog.core.insights.domain.Insight.Basis;
import com.moneylog.core.insights.domain.Insight.Confidence;
import com.moneylog.core.insights.domain.Insight.Period;
import com.moneylog.core.insights.domain.Insight.Severity;
import com.moneylog.core.insights.domain.InsufficientData.Reason;
import com.moneylog.core.sk.money.Money;

public final class DailyInsights {

    public static final String DAILY_BUDGET = "DAILY_BUDGET";
    public static final String TODAY_VS_AVERAGE = "TODAY_VS_AVERAGE";
    public static final String DUE_SOON = "DUE_SOON";
    public static final String MIN_BALANCE_FORECAST = "MIN_BALANCE_FORECAST";
    public static final String ATYPICAL_ENTRY = "ATYPICAL_ENTRY";
    public static final String CARD_CLOSING_SOON = "CARD_CLOSING_SOON";

    private DailyInsights() {
    }

    public static void compute(DayFacts f, InsightSink sink) {
        dailyBudget(f, sink);
        todayVsAverage(f, sink);
        dueSoon(f, sink);
        minBalanceForecast(f, sink);
        atypicalEntry(f, sink);
        cardClosingSoon(f, sink);
    }

    static void dailyBudget(DayFacts f, InsightSink sink) {
        MonthFacts.Budget budget = f.budget();
        if (budget == null || !budget.received().isPositive()) {
            sink.insufficient(DAILY_BUDGET, Reason.NO_INCOME);
            return;
        }
        int daysLeft = f.daysInMonth() - f.dayOfMonth() + 1;
        Money free = budget.free();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("daysLeft", String.valueOf(daysLeft));
        if (free.isNegative()) {
            params.put("amount", Fmt.zero().toPlainString());
            params.put("overBy", free.abs().toPlainString());
            sink.emit(day(f, DAILY_BUDGET, "insight.daily.over", params, List.of("summary." + java.time.YearMonth.from(f.date())), Severity.ALERT,
                    Thresholds.PRIORITY_MAX, null, null, null, free.abs().cents()));
            return;
        }
        Money perDay = Fmt.money(Fmt.dec(free).divide(BigDecimal.valueOf(daysLeft), 0, RoundingMode.DOWN));
        params.put("amount", perDay.toPlainString());
        params.put("free", free.toPlainString());
        sink.emit(day(f, DAILY_BUDGET, "insight.daily.budget", params, List.of("summary." + java.time.YearMonth.from(f.date())), Severity.INFO,
                Thresholds.PRIORITY_MAX, null, null, null, perDay.cents()));
    }

    static void todayVsAverage(DayFacts f, InsightSink sink) {
        if (f.dayOfMonth() < Thresholds.TODAY_VS_AVERAGE_MIN_DAYS || f.variableBefore().isEmpty()) {
            sink.insufficient(TODAY_VS_AVERAGE, Reason.PARTIAL_MONTH, Math.max(0, Thresholds.TODAY_VS_AVERAGE_MIN_DAYS - f.dayOfMonth()));
            return;
        }
        BigDecimal average = Fmt.ratio(Stats.sum(f.variableBefore().stream().map(Fmt::dec).toList()), BigDecimal.valueOf(f.variableBefore().size()));
        if (average.signum() <= 0) {
            sink.insufficient(TODAY_VS_AVERAGE, Reason.NO_BASELINE);
            return;
        }
        BigDecimal ratio = Fmt.dec(f.variableToday()).divide(average, Stats.SCALE, RoundingMode.HALF_UP);
        if (ratio.compareTo(Thresholds.TODAY_VS_AVERAGE_RATIO) < 0 || f.variableToday().isLessThan(Thresholds.MATERIAL_50)) {
            return;
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("today", f.variableToday().toPlainString());
        params.put("average", Fmt.money(average).toPlainString());
        params.put("ratio", Fmt.number(ratio, 2));
        sink.emit(day(f, TODAY_VS_AVERAGE, "insight.today.high", params, List.of("spending.daily"), Severity.ATTENTION, 70, Basis.HISTORY,
                new Confidence(f.variableBefore().size(), f.dayOfMonth() - 1, null), null, f.variableToday().cents()));
    }

    static void dueSoon(DayFacts f, InsightSink sink) {
        if (f.dueWeek().isEmpty()) {
            return;
        }
        Money total = Money.sum(f.dueWeek().stream().map(DayFacts.Due::amount).toList(), Fmt.zero().currency());
        Money dueToday = Money.sum(f.dueWeek().stream().filter(d -> !d.dueDate().isAfter(f.date())).map(DayFacts.Due::amount).toList(),
                Fmt.zero().currency());
        boolean exceeds = f.hasCashAccounts() && total.isGreaterThan(f.cashBalance());
        Severity severity;
        String key;
        if (exceeds) {
            severity = Severity.ALERT;
            key = "insight.due.exceeds";
        } else if (dueToday.isPositive()) {
            severity = Severity.ATTENTION;
            key = "insight.due.today";
        } else {
            severity = Severity.INFO;
            key = "insight.due.week";
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("total", total.toPlainString());
        params.put("count", String.valueOf(f.dueWeek().size()));
        params.put("today", dueToday.toPlainString());
        if (f.hasCashAccounts()) {
            params.put("balance", f.cashBalance().toPlainString());
        }
        List<String> refs = new java.util.ArrayList<>(List.of("commitments"));
        f.dueWeek().stream().map(DayFacts.Due::ref).filter(r -> r != null).distinct().forEach(refs::add);
        sink.emit(day(f, DUE_SOON, key, params, refs, severity, 85, Basis.KNOWN_SCHEDULE, null, null, total.cents()));
    }

    static void minBalanceForecast(DayFacts f, InsightSink sink) {
        if (!f.hasCashAccounts()) {
            sink.insufficient(MIN_BALANCE_FORECAST, Reason.NO_ACCOUNTS);
            return;
        }
        if (f.events().isEmpty() && !f.cashBalance().isNegative()) {
            sink.insufficient(MIN_BALANCE_FORECAST, Reason.NO_SCHEDULE);
            return;
        }
        Map<Integer, Money> byOffset = new TreeMap<>();
        for (DayFacts.CashEvent e : f.events()) {
            long offset = Math.max(0, ChronoUnit.DAYS.between(f.date(), e.date()));
            if (offset <= Thresholds.FORECAST_DAYS) {
                byOffset.merge((int) offset, e.signedAmount(), Money::add);
            }
        }
        Money balance = f.cashBalance();
        Money min = balance;
        int minDay = 0;
        Integer firstNegative = balance.isNegative() ? 0 : null;
        for (int i = 0; i <= Thresholds.FORECAST_DAYS; i++) {
            Money change = byOffset.get(i);
            if (change != null) {
                balance = balance.add(change);
            }
            if (balance.isLessThan(min)) {
                min = balance;
                minDay = i;
            }
            if (firstNegative == null && balance.isNegative()) {
                firstNegative = i;
            }
        }
        if (firstNegative == null) {
            return;
        }
        boolean soon = firstNegative <= Thresholds.FORECAST_ALERT_DAYS;
        Map<String, String> params = new LinkedHashMap<>();
        params.put("minBalance", min.toPlainString());
        params.put("minDate", f.date().plusDays(minDay).toString());
        params.put("firstNegativeDate", f.date().plusDays(firstNegative).toString());
        params.put("daysUntil", String.valueOf(firstNegative));
        params.put("balance", f.cashBalance().toPlainString());
        sink.emit(day(f, MIN_BALANCE_FORECAST, soon ? "insight.balance.negativeSoon" : "insight.balance.negativeLater", params,
                List.of("accounts.balance", "commitments"), soon ? Severity.ALERT : Severity.ATTENTION, 95, Basis.KNOWN_SCHEDULE, null, null,
                min.abs().cents()));
    }

    static void atypicalEntry(DayFacts f, InsightSink sink) {
        if (f.todayEntries().isEmpty()) {
            return;
        }
        boolean evaluated = false;
        for (DayFacts.TodayEntry entry : f.todayEntries()) {
            List<Money> history = f.categoryHistory().getOrDefault(entry.categoryId(), List.of());
            int n = history.size();
            if (n < Thresholds.ATYPICAL_MIN_N) {
                continue;
            }
            evaluated = true;
            List<BigDecimal> series = history.stream().map(Fmt::dec).toList();
            BigDecimal x = Fmt.dec(entry.amount());
            BigDecimal median = Stats.median(series);
            boolean atypical;
            BigDecimal p95 = null;
            BigDecimal z = null;
            if (n >= Thresholds.ATYPICAL_P95_MIN_N) {
                p95 = Stats.percentile(series, Thresholds.ATYPICAL_P);
                atypical = x.compareTo(p95) > 0;
            } else {
                z = Stats.robustZ(x, series);
                atypical = z.compareTo(Thresholds.Z_ALERT) >= 0;
            }
            if (!atypical || x.compareTo(median.multiply(Thresholds.ATYPICAL_MIN_MULTIPLE)) < 0
                    || entry.amount().isLessThan(Thresholds.MATERIAL_50)) {
                continue;
            }
            Map<String, String> params = new LinkedHashMap<>();
            params.put("categoryId", entry.categoryId().toString());
            params.put("categoryName", f.categoryNames().getOrDefault(entry.categoryId(), ""));
            params.put("amount", entry.amount().toPlainString());
            params.put("median", Fmt.money(median).toPlainString());
            if (p95 != null) {
                params.put("p95", Fmt.money(p95).toPlainString());
            }
            sink.emit(day(f, ATYPICAL_ENTRY, "insight.entry.atypical", params, List.of("transaction." + entry.subjectId()), Severity.ATTENTION, 68,
                    Basis.HISTORY, new Confidence(n, Thresholds.HISTORY_MONTHS, z == null ? null : Fmt.number(z, 2)), entry.subjectId(),
                    entry.amount().cents()));
        }
        if (!evaluated) {
            sink.insufficient(ATYPICAL_ENTRY, Reason.HISTORY);
        }
    }

    static void cardClosingSoon(DayFacts f, InsightSink sink) {
        for (DayFacts.Closing closing : f.closings()) {
            long days = ChronoUnit.DAYS.between(f.date(), closing.closingDate());
            if (days < 0 || days > Thresholds.CLOSING_SOON_DAYS) {
                continue;
            }
            Map<String, String> params = new LinkedHashMap<>();
            params.put("cardName", closing.cardName());
            params.put("closingDate", closing.closingDate().toString());
            params.put("daysUntil", String.valueOf(days));
            params.put("partial", closing.partial().toPlainString());
            sink.emit(day(f, CARD_CLOSING_SOON, "insight.card.closing", params, List.of(closing.ref()),
                    days <= Thresholds.CLOSING_ATTENTION_DAYS ? Severity.ATTENTION : Severity.INFO, 45, null, null, closing.ref(),
                    closing.partial().cents()));
        }
    }

    private static Insight day(DayFacts f, String type, String key, Map<String, String> params, List<String> refs, Severity severity,
                               int priority, Basis basis, Confidence confidence, String subjectId, long magnitude) {
        return new Insight(type, key, params, refs, severity, priority, new Period(Period.Kind.DAY, f.date().toString()), basis, confidence,
                subjectId, magnitude);
    }
}
