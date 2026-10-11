package com.moneylog.core.insights.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.insights.domain.Insight.Basis;
import com.moneylog.core.insights.domain.Insight.Confidence;
import com.moneylog.core.insights.domain.Insight.Period;
import com.moneylog.core.insights.domain.Insight.Severity;
import com.moneylog.core.insights.domain.InsufficientData.Reason;
import com.moneylog.core.sk.money.Money;

public final class MonthlyInsights {

    public static final String SAVINGS_RATE = "SAVINGS_RATE";
    public static final String SPEND_VS_BASELINE = "SPEND_VS_BASELINE";
    public static final String CATEGORY_DELTA = "CATEGORY_DELTA";
    public static final String MONTH_PROJECTION = "MONTH_PROJECTION";
    public static final String FREE_MONEY = "FREE_MONEY";
    public static final String COMMITMENT_INDEX = "COMMITMENT_INDEX";
    public static final String DEBT_SERVICE = "DEBT_SERVICE";
    public static final String INSTALLMENT_LOAD = "INSTALLMENT_LOAD";
    public static final String EMERGENCY_COVERAGE = "EMERGENCY_COVERAGE";
    public static final String INCOME_STABILITY = "INCOME_STABILITY";
    public static final String GOAL_PACE = "GOAL_PACE";
    public static final String CONCENTRATION = "CONCENTRATION";
    public static final String CARD_COMMITTED = "CARD_COMMITTED";
    public static final String CARD_LIMIT_USAGE = "CARD_LIMIT_USAGE";
    public static final String RECURRING_DETECTED = "RECURRING_DETECTED";
    public static final String ALL = "ALL";

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);

    private MonthlyInsights() {
    }

    public record Projection(Money closing, Money lower, Money upper, int spendDays, int daysElapsed, BigDecimal dailyAverage) {
    }

    public static void compute(MonthFacts f, InsightSink sink) {
        if (f.futureMonth()) {
            sink.insufficient(ALL, Reason.FUTURE_MONTH);
            return;
        }
        Projection projection = projection(f, sink);
        savingsRate(f, projection, sink);
        spendVsBaseline(f, projection, sink);
        categoryDelta(f, sink);
        freeMoney(f, sink);
        commitmentIndex(f, sink);
        debtService(f, sink);
        installmentLoad(f, sink);
        emergencyCoverage(f, sink);
        incomeStability(f, sink);
        goalPace(f, sink);
        concentration(f, sink);
        cardCommitted(f, sink);
        cardLimitUsage(f, sink);
        recurringDetected(f, sink);
    }

    static Projection projection(MonthFacts f, InsightSink sink) {
        if (!f.currentMonth() || f.budget() == null) {
            return null;
        }
        int d = f.daysElapsed();
        long spendDays = f.dailyVariable().stream().filter(Money::isPositive).count();
        if (d < Thresholds.PROJECTION_MIN_DAYS || spendDays < Thresholds.PROJECTION_MIN_SPEND_DAYS || f.dailyVariable().size() != d) {
            sink.insufficient(MONTH_PROJECTION, Reason.PARTIAL_MONTH, Math.max(0, Thresholds.PROJECTION_MIN_DAYS - d));
            return null;
        }
        List<BigDecimal> daily = f.dailyVariable().stream().map(Fmt::dec).toList();
        BigDecimal average = Fmt.ratio(Stats.sum(daily), BigDecimal.valueOf(d));
        int remaining = f.daysInMonth() - d;
        BigDecimal base = Fmt.dec(f.current().consumption()).add(Fmt.dec(f.budget().pendingRecurring()));
        BigDecimal closing = base.add(average.multiply(BigDecimal.valueOf(remaining)));
        BigDecimal margin = Thresholds.INTERVAL_80_Z.multiply(Stats.sampleStdDev(daily))
                .multiply(BigDecimal.valueOf(remaining).sqrt(MC));
        Projection projection = new Projection(Fmt.money(closing), Fmt.money(closing.subtract(margin)), Fmt.money(closing.add(margin)),
                (int) spendDays, d, average);
        emitProjection(f, projection, sink);
        return projection;
    }

    private static void emitProjection(MonthFacts f, Projection p, InsightSink sink) {
        Money received = f.budget().received();
        if (!received.isPositive()) {
            return;
        }
        Severity severity;
        String key;
        if (p.closing().isGreaterThan(received)) {
            severity = Severity.ALERT;
            key = "insight.projection.over";
        } else if (p.upper().isGreaterThan(received)) {
            severity = Severity.ATTENTION;
            key = "insight.projection.near";
        } else {
            return;
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("closing", p.closing().toPlainString());
        params.put("lower", p.lower().toPlainString());
        params.put("upper", p.upper().toPlainString());
        params.put("received", received.toPlainString());
        params.put("daysLeft", String.valueOf(f.daysInMonth() - p.daysElapsed()));
        sink.emit(month(f, MONTH_PROJECTION, key, params, List.of("summary." + f.month()), severity, 90, Basis.PROJECTION,
                new Confidence(p.spendDays(), p.daysElapsed(), null), null, p.closing().subtract(received).abs().cents()));
    }

    static void savingsRate(MonthFacts f, Projection projection, InsightSink sink) {
        Money income = f.current().income();
        if (!income.isPositive()) {
            sink.insufficient(SAVINGS_RATE, Reason.NO_INCOME);
            return;
        }
        List<MonthSnapshot> base = f.history().stream().filter(m -> m.income().isPositive()).toList();
        if (base.size() < Thresholds.SAVINGS_WINDOW) {
            sink.insufficient(SAVINGS_RATE, Reason.HISTORY, Thresholds.SAVINGS_WINDOW - base.size());
            return;
        }
        Money consumption = f.current().consumption();
        if (f.currentMonth()) {
            if (projection == null) {
                sink.insufficient(SAVINGS_RATE, Reason.PARTIAL_MONTH);
                return;
            }
            consumption = projection.closing();
        }
        BigDecimal rate = Fmt.ratio(income.subtract(consumption), income);
        List<BigDecimal> rates = base.subList(base.size() - Thresholds.SAVINGS_WINDOW, base.size()).stream()
                .map(m -> Fmt.ratio(m.income().subtract(m.consumption()), m.income())).toList();
        BigDecimal average = Stats.mean(rates);
        BigDecimal deltaPp = rate.subtract(average).multiply(BigDecimal.valueOf(100));
        Severity severity;
        String key;
        if (rate.signum() < 0) {
            severity = Severity.ALERT;
            key = "insight.savings.rate.negative";
        } else if (deltaPp.compareTo(Thresholds.SAVINGS_DELTA_PP.negate()) <= 0) {
            severity = Severity.ATTENTION;
            key = "insight.savings.rate.down";
        } else if (deltaPp.compareTo(Thresholds.SAVINGS_DELTA_PP) >= 0) {
            severity = Severity.POSITIVE;
            key = "insight.savings.rate.up";
        } else {
            severity = Severity.INFO;
            key = "insight.savings.rate.flat";
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("rate", Fmt.percent(rate));
        params.put("average", Fmt.percent(average));
        params.put("deltaPp", Fmt.number(deltaPp, 2));
        params.put("income", income.toPlainString());
        params.put("consumption", consumption.toPlainString());
        params.put("projected", String.valueOf(f.currentMonth()));
        sink.emit(month(f, SAVINGS_RATE, key, params, List.of("summary." + f.month()), severity, 70,
                f.currentMonth() ? Basis.PROJECTION : Basis.HISTORY, new Confidence(Thresholds.SAVINGS_WINDOW, Thresholds.SAVINGS_WINDOW, null),
                null, income.subtract(consumption).abs().cents()));
    }

    static void spendVsBaseline(MonthFacts f, Projection projection, InsightSink sink) {
        if (f.history().size() < Thresholds.MIN_HISTORY_MONTHS) {
            sink.insufficient(SPEND_VS_BASELINE, Reason.HISTORY, Thresholds.MIN_HISTORY_MONTHS - f.history().size());
            return;
        }
        Money spend = f.current().consumption();
        if (f.currentMonth()) {
            if (f.daysElapsed() < Thresholds.SPEND_MIN_DAYS || projection == null) {
                sink.insufficient(SPEND_VS_BASELINE, Reason.PARTIAL_MONTH, Math.max(0, Thresholds.SPEND_MIN_DAYS - f.daysElapsed()));
                return;
            }
            spend = projection.closing();
        }
        List<BigDecimal> series = f.history().stream().map(m -> Fmt.dec(m.consumption())).toList();
        BigDecimal median = Stats.median(series);
        if (median.signum() <= 0) {
            sink.insufficient(SPEND_VS_BASELINE, Reason.NO_BASELINE);
            return;
        }
        BigDecimal z = Stats.robustZ(Fmt.dec(spend), series);
        BigDecimal delta = Fmt.dec(spend).subtract(median);
        boolean material = delta.abs().compareTo(median.multiply(Thresholds.SPEND_MIN_RELATIVE)) >= 0
                && delta.abs().compareTo(Fmt.dec(Thresholds.MATERIAL_100)) >= 0;
        if (!material) {
            return;
        }
        Severity severity;
        String key;
        if (z.compareTo(Thresholds.Z_ALERT) >= 0) {
            severity = Severity.ALERT;
            key = "insight.spend.high";
        } else if (z.compareTo(Thresholds.Z_ATTENTION) >= 0) {
            severity = Severity.ATTENTION;
            key = "insight.spend.high";
        } else if (z.compareTo(Thresholds.Z_ATTENTION.negate()) <= 0) {
            severity = Severity.POSITIVE;
            key = "insight.spend.low";
        } else {
            return;
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("amount", spend.toPlainString());
        params.put("median", Fmt.money(median).toPlainString());
        params.put("deltaAmount", Fmt.money(delta).toPlainString());
        params.put("deltaPercent", Fmt.percent(Fmt.ratio(delta, median)));
        params.put("z", Fmt.number(z, 2));
        params.put("projected", String.valueOf(f.currentMonth()));
        sink.emit(month(f, SPEND_VS_BASELINE, key, params, List.of("summary." + f.month()), severity, 80, Basis.HISTORY,
                new Confidence(series.size(), Thresholds.HISTORY_MONTHS, Fmt.number(z, 2)), null, delta.abs().longValue()));
    }

    static void categoryDelta(MonthFacts f, InsightSink sink) {
        if (f.history().isEmpty()) {
            sink.insufficient(CATEGORY_DELTA, Reason.HISTORY, 1);
            return;
        }
        List<MonthSnapshot> window = f.history().subList(Math.max(0, f.history().size() - Thresholds.CATEGORY_WINDOW), f.history().size());
        boolean median3 = window.size() >= Thresholds.CATEGORY_WINDOW;
        MonthSnapshot previous = window.get(window.size() - 1);
        if (!median3 && !previous.month().equals(f.month().minusMonths(1))) {
            sink.insufficient(CATEGORY_DELTA, Reason.HISTORY, Thresholds.CATEGORY_WINDOW - window.size());
            return;
        }
        List<Map.Entry<UUID, Money>> categories = f.current().byCategory().entrySet().stream()
                .filter(e -> e.getValue().isPositive())
                .sorted(Map.Entry.<UUID, Money>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey())).toList();
        for (Map.Entry<UUID, Money> entry : categories) {
            UUID category = entry.getKey();
            Money now = entry.getValue();
            List<BigDecimal> values = window.stream().map(m -> Fmt.dec(m.categoryFloorZero(category))).toList();
            BigDecimal baseline = median3 ? Stats.median(values) : values.get(values.size() - 1);
            if (baseline.signum() <= 0) {
                continue;
            }
            BigDecimal delta = Fmt.dec(now).subtract(baseline);
            BigDecimal percent = Fmt.ratio(delta, baseline).multiply(BigDecimal.valueOf(100));
            BigDecimal minPercent = median3 ? Thresholds.CATEGORY_MIN_PERCENT : Thresholds.CATEGORY_LEGACY_MIN_PERCENT;
            if (percent.abs().compareTo(minPercent) < 0 || delta.abs().compareTo(Fmt.dec(Thresholds.MATERIAL_50)) < 0) {
                continue;
            }
            boolean up = delta.signum() > 0;
            if (!up && f.currentMonth()) {
                continue;
            }
            Severity severity = up ? Severity.ATTENTION : Severity.POSITIVE;
            BigDecimal z = null;
            if (median3) {
                z = Stats.robustZ(Fmt.dec(now), values);
                if (up && z.compareTo(Thresholds.Z_ALERT) >= 0 && delta.compareTo(Fmt.dec(Thresholds.MATERIAL_200)) >= 0) {
                    severity = Severity.ALERT;
                }
            }
            Map<String, String> params = new LinkedHashMap<>();
            params.put("categoryId", category.toString());
            params.put("categoryName", String.valueOf(f.categoryNames().getOrDefault(category, "")));
            params.put("current", now.toPlainString());
            params.put("previous", Fmt.money(baseline).toPlainString());
            params.put("deltaAmount", Fmt.money(delta).toPlainString());
            params.put("deltaPercent", Fmt.number(percent, 1));
            params.put("baseline", median3 ? "MEDIAN_3" : "PREVIOUS_MONTH");
            params.put("months", String.valueOf(window.size()));
            sink.emit(month(f, CATEGORY_DELTA, up ? "insight.category.up" : "insight.category.down", params, List.of("category." + category),
                    severity, 60, Basis.HISTORY, new Confidence(window.size(), Thresholds.CATEGORY_WINDOW, z == null ? null : Fmt.number(z, 2)),
                    category.toString(), delta.abs().longValue()));
        }
    }

    static void freeMoney(MonthFacts f, InsightSink sink) {
        MonthFacts.Budget budget = f.budget();
        if (!f.currentMonth() || budget == null) {
            return;
        }
        if (!budget.received().isPositive()) {
            sink.insufficient(FREE_MONEY, Reason.NO_INCOME);
            return;
        }
        Money free = budget.free();
        Severity severity;
        String key;
        if (free.isNegative()) {
            severity = Severity.ALERT;
            key = "insight.freeMoney.negative";
        } else if (Fmt.dec(free).compareTo(Fmt.dec(budget.received()).multiply(Thresholds.FREE_LOW_RATIO)) < 0) {
            severity = Severity.ATTENTION;
            key = "insight.freeMoney.low";
        } else {
            severity = Severity.INFO;
            key = "insight.freeMoney.ok";
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("received", budget.received().toPlainString());
        params.put("spent", budget.spentSoFar().toPlainString());
        params.put("upcoming", budget.upcoming().toPlainString());
        params.put("reserved", budget.reserved().toPlainString());
        params.put("free", free.toPlainString());
        sink.emit(month(f, FREE_MONEY, key, params, List.of("summary." + f.month()), severity, 75, null, null, null, free.abs().cents()));
    }

    static void commitmentIndex(MonthFacts f, InsightSink sink) {
        Money income = f.current().income();
        if (!income.isPositive()) {
            sink.insufficient(COMMITMENT_INDEX, Reason.NO_INCOME);
            return;
        }
        Money committed = f.recurringOut().add(f.installments());
        BigDecimal index = Fmt.ratio(committed, income);
        Severity severity;
        if (index.compareTo(Thresholds.COMMITMENT_ALERT) >= 0) {
            severity = Severity.ALERT;
        } else if (index.compareTo(Thresholds.COMMITMENT_ATTENTION) >= 0) {
            severity = Severity.ATTENTION;
        } else {
            return;
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("fixed", f.recurringOut().toPlainString());
        params.put("installments", f.installments().toPlainString());
        params.put("income", income.toPlainString());
        params.put("percent", Fmt.percent(index));
        sink.emit(month(f, COMMITMENT_INDEX, "insight.commitment.high", params, List.of("summary." + f.month(), "recurrences"), severity, 65,
                null, null, null, committed.cents()));
    }

    static void debtService(MonthFacts f, InsightSink sink) {
        Money service = f.installments().add(f.debtPaymentsDue());
        if (!service.isPositive() && !f.debtTotal().isPositive()) {
            return;
        }
        Money income = f.current().income();
        if (!income.isPositive()) {
            sink.insufficient(DEBT_SERVICE, Reason.NO_INCOME);
            return;
        }
        BigDecimal ratio = Fmt.ratio(service, income);
        Severity severity;
        if (ratio.compareTo(Thresholds.DEBT_SERVICE_ALERT) >= 0) {
            severity = Severity.ALERT;
        } else if (ratio.compareTo(Thresholds.DEBT_SERVICE_ATTENTION) >= 0) {
            severity = Severity.ATTENTION;
        } else {
            return;
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("service", service.toPlainString());
        params.put("installments", f.installments().toPlainString());
        params.put("debtPayments", f.debtPaymentsDue().toPlainString());
        params.put("income", income.toPlainString());
        params.put("percent", Fmt.percent(ratio));
        params.put("debtTotal", f.debtTotal().toPlainString());
        params.put("debtToAnnualIncome", Fmt.percent(Fmt.ratio(Fmt.dec(f.debtTotal()), Fmt.dec(income).multiply(BigDecimal.valueOf(12)))));
        sink.emit(month(f, DEBT_SERVICE, "insight.debt.service.high", params, List.of("summary." + f.month(), "debts.overview"), severity, 66,
                null, null, null, service.cents()));
    }

    static void installmentLoad(MonthFacts f, InsightSink sink) {
        List<Money> load = f.installmentLoad();
        if (load.isEmpty() || load.stream().noneMatch(Money::isPositive)) {
            sink.insufficient(INSTALLMENT_LOAD, Reason.NO_DATA);
            return;
        }
        Money current = load.get(0);
        int peakIndex = 1;
        for (int k = 1; k < load.size(); k++) {
            if (load.get(k).isGreaterThan(load.get(peakIndex))) {
                peakIndex = k;
            }
        }
        Money peak = load.get(peakIndex);
        BigDecimal threshold = Fmt.dec(current).multiply(Thresholds.INSTALLMENT_PEAK_RATIO);
        boolean rises = Fmt.dec(peak).compareTo(threshold) >= 0 && peak.subtract(current).cents() >= Thresholds.MATERIAL_100.cents();
        if (rises) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("current", current.toPlainString());
            params.put("peak", peak.toPlainString());
            params.put("peakMonth", f.month().plusMonths(peakIndex).toString());
            params.put("increase", peak.subtract(current).toPlainString());
            sink.emit(month(f, INSTALLMENT_LOAD, "insight.installments.peak", params, List.of("installments"), Severity.ATTENTION, 55, null,
                    null, null, peak.subtract(current).cents()));
            return;
        }
        if (load.size() > Thresholds.INSTALLMENT_DROP_MONTHS) {
            Money later = load.get(Thresholds.INSTALLMENT_DROP_MONTHS);
            boolean drops = Fmt.dec(later).compareTo(Fmt.dec(current).multiply(Thresholds.INSTALLMENT_DROP_RATIO)) <= 0
                    && current.subtract(later).cents() >= Thresholds.MATERIAL_100.cents();
            if (drops) {
                Map<String, String> params = new LinkedHashMap<>();
                params.put("current", current.toPlainString());
                params.put("later", later.toPlainString());
                params.put("laterMonth", f.month().plusMonths(Thresholds.INSTALLMENT_DROP_MONTHS).toString());
                params.put("dropPercent", Fmt.percent(Fmt.ratio(current.subtract(later), current)));
                sink.emit(month(f, INSTALLMENT_LOAD, "insight.installments.drop", params, List.of("installments"), Severity.POSITIVE, 50, null,
                        null, null, current.subtract(later).cents()));
            }
        }
    }

    static void emergencyCoverage(MonthFacts f, InsightSink sink) {
        if (!f.currentMonth()) {
            return;
        }
        if (!f.hasCashAccounts()) {
            sink.insufficient(EMERGENCY_COVERAGE, Reason.NO_ACCOUNTS);
            return;
        }
        if (f.history().size() < Thresholds.MIN_HISTORY_MONTHS) {
            sink.insufficient(EMERGENCY_COVERAGE, Reason.HISTORY, Thresholds.MIN_HISTORY_MONTHS - f.history().size());
            return;
        }
        List<MonthSnapshot> last3 = f.history().subList(f.history().size() - Thresholds.MIN_HISTORY_MONTHS, f.history().size());
        BigDecimal essential = Stats.mean(last3.stream().map(m -> Fmt.dec(m.essential())).toList());
        if (essential.signum() <= 0) {
            sink.insufficient(EMERGENCY_COVERAGE, Reason.NO_DATA);
            return;
        }
        BigDecimal balance = Fmt.dec(f.cashBalance());
        BigDecimal months = balance.signum() <= 0 ? BigDecimal.ZERO : balance.divide(essential, 2, RoundingMode.HALF_UP);
        BigDecimal total = Stats.mean(last3.stream().map(m -> Fmt.dec(m.consumption())).toList());
        Severity severity;
        String key;
        if (months.compareTo(Thresholds.COVERAGE_ALERT_MONTHS) < 0) {
            severity = Severity.ALERT;
            key = "insight.coverage.low";
        } else if (months.compareTo(Thresholds.COVERAGE_ATTENTION_MONTHS) < 0) {
            severity = Severity.ATTENTION;
            key = "insight.coverage.low";
        } else if (months.compareTo(Thresholds.COVERAGE_OK_MONTHS) >= 0) {
            severity = Severity.POSITIVE;
            key = "insight.coverage.ok";
        } else {
            return;
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("months", Fmt.number(months, 2));
        params.put("balance", f.cashBalance().toPlainString());
        params.put("essentialMonthly", Fmt.money(essential).toPlainString());
        if (total.signum() > 0) {
            params.put("runwayMonths", balance.signum() <= 0 ? "0.00" : Fmt.number(balance.divide(total, 2, RoundingMode.HALF_UP), 2));
        }
        sink.emit(month(f, EMERGENCY_COVERAGE, key, params, List.of("accounts.balance"), severity, 72, Basis.HISTORY,
                new Confidence(Thresholds.MIN_HISTORY_MONTHS, Thresholds.MIN_HISTORY_MONTHS, null), null, f.cashBalance().abs().cents()));
    }

    static void incomeStability(MonthFacts f, InsightSink sink) {
        List<MonthSnapshot> withIncome = f.history().stream().filter(m -> m.income().isPositive()).toList();
        if (withIncome.size() < Thresholds.MIN_INCOME_STABILITY_MONTHS) {
            sink.insufficient(INCOME_STABILITY, Reason.HISTORY, Thresholds.MIN_INCOME_STABILITY_MONTHS - withIncome.size());
            return;
        }
        List<BigDecimal> incomes = withIncome.stream().map(m -> Fmt.dec(m.income())).toList();
        BigDecimal cv = Stats.coefficientOfVariation(incomes);
        if (cv == null || cv.compareTo(Thresholds.INCOME_CV_ATTENTION) < 0) {
            return;
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("cv", Fmt.number(cv, 4));
        params.put("average", Fmt.money(Stats.mean(incomes)).toPlainString());
        params.put("months", String.valueOf(incomes.size()));
        sink.emit(month(f, INCOME_STABILITY, "insight.income.unstable", params, List.of("income.series"), Severity.ATTENTION, 20, Basis.HISTORY,
                new Confidence(incomes.size(), Thresholds.HISTORY_MONTHS, null), null, 0));
    }

    static void goalPace(MonthFacts f, InsightSink sink) {
        if (!f.currentMonth() || f.goals().isEmpty()) {
            return;
        }
        if (f.goals().stream().noneMatch(MonthFacts.GoalFact::hasPlan)) {
            sink.insufficient(GOAL_PACE, Reason.NO_PLAN);
            return;
        }
        for (MonthFacts.GoalFact goal : f.goals()) {
            if (!goal.hasPlan()) {
                continue;
            }
            Map<String, String> params = new LinkedHashMap<>();
            params.put("goalId", goal.goalId().toString());
            params.put("goalName", goal.name());
            params.put("requiredPace", goal.requiredPace().toPlainString());
            params.put("monthsLeft", String.valueOf(goal.monthsLeft()));
            if (!goal.onTrack()) {
                params.put("gap", goal.gap().toPlainString());
                sink.emit(month(f, GOAL_PACE, "insight.goal.behind", params, List.of("goal." + goal.goalId()), Severity.ATTENTION, 58, null,
                        null, goal.goalId().toString(), goal.gap().abs().cents()));
            } else if (goal.streak() >= Thresholds.GOAL_STREAK_AHEAD) {
                params.put("streak", String.valueOf(goal.streak()));
                sink.emit(month(f, GOAL_PACE, "insight.goal.ahead", params, List.of("goal." + goal.goalId()), Severity.POSITIVE, 40, null,
                        null, goal.goalId().toString(), 0));
            }
        }
    }

    static void concentration(MonthFacts f, InsightSink sink) {
        List<Money> positives = f.current().byCategory().values().stream().filter(Money::isPositive)
                .sorted(Comparator.<Money>naturalOrder().reversed()).toList();
        if (positives.size() < Thresholds.CONCENTRATION_MIN_CATEGORIES) {
            return;
        }
        Money total = Money.sum(positives, positives.get(0).currency());
        if (total.isLessThan(Thresholds.MATERIAL_100)) {
            return;
        }
        Money top3 = Money.sum(positives.subList(0, 3), total.currency());
        BigDecimal share = Fmt.ratio(top3, total);
        if (share.compareTo(Thresholds.CONCENTRATION_MIN_SHARE) < 0) {
            return;
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("share", Fmt.percent(share));
        params.put("top3", top3.toPlainString());
        params.put("total", total.toPlainString());
        sink.emit(month(f, CONCENTRATION, "insight.concentration.top3", params, List.of("summary." + f.month()), Severity.INFO, 1, null, null,
                null, top3.cents()));
    }

    static void cardCommitted(MonthFacts f, InsightSink sink) {
        if (f.cardOutstanding() == null || !f.cardOutstanding().isPositive()) {
            return;
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("amount", f.cardOutstanding().toPlainString());
        sink.emit(month(f, CARD_COMMITTED, "insight.card.committed", params, List.of("cardOutstanding"), Severity.INFO, 30, null, null, null,
                f.cardOutstanding().cents()));
    }

    static void cardLimitUsage(MonthFacts f, InsightSink sink) {
        if (!f.currentMonth() || f.cards().isEmpty()) {
            return;
        }
        if (f.cards().stream().allMatch(c -> c.limit() == null)) {
            sink.insufficient(CARD_LIMIT_USAGE, Reason.NO_LIMIT);
            return;
        }
        for (MonthFacts.CardFact card : f.cards()) {
            if (card.limit() == null || !card.limit().isPositive()) {
                continue;
            }
            BigDecimal usage = Fmt.ratio(card.used(), card.limit());
            Severity severity;
            if (usage.compareTo(Thresholds.CARD_LIMIT_ALERT) >= 0) {
                severity = Severity.ALERT;
            } else if (usage.compareTo(Thresholds.CARD_LIMIT_ATTENTION) >= 0) {
                severity = Severity.ATTENTION;
            } else {
                continue;
            }
            Map<String, String> params = new LinkedHashMap<>();
            params.put("cardId", card.cardId().toString());
            params.put("cardName", card.name());
            params.put("used", card.used().toPlainString());
            params.put("limit", card.limit().toPlainString());
            params.put("percent", Fmt.percent(usage));
            sink.emit(month(f, CARD_LIMIT_USAGE, "insight.card.limit", params, List.of("card." + card.cardId()), severity, 62, null, null,
                    card.cardId().toString(), card.used().cents()));
        }
    }

    static void recurringDetected(MonthFacts f, InsightSink sink) {
        if (f.subscriptionLines().isEmpty()) {
            return;
        }
        java.time.LocalDate asOf = f.currentMonth() ? f.today() : f.month().lastDay();
        for (SubscriptionDetector.Detected d : SubscriptionDetector.detect(f.subscriptionLines(), f.registeredDescriptions(), asOf)) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("description", d.description());
            params.put("amount", d.typicalAmount().toPlainString());
            params.put("annualCost", d.annualCost().toPlainString());
            params.put("occurrences", String.valueOf(d.occurrences()));
            params.put("intervalDays", String.valueOf(d.intervalDays()));
            sink.emit(month(f, RECURRING_DETECTED, "insight.recurring.detected", params, List.of("transactions"), Severity.INFO, 10, Basis.HISTORY,
                    new Confidence(d.occurrences(), Thresholds.HISTORY_MONTHS, null), d.key(), d.annualCost().cents()));
        }
    }

    private static Insight month(MonthFacts f, String type, String key, Map<String, String> params, List<String> refs, Severity severity,
                                 int priority, Basis basis, Confidence confidence, String subjectId, long magnitude) {
        return new Insight(type, key, params, refs, severity, priority, new Period(Period.Kind.MONTH, f.month().toString()), basis, confidence,
                subjectId, magnitude);
    }
}
