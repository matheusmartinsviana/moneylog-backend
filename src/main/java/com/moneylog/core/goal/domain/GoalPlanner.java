package com.moneylog.core.goal.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

public final class GoalPlanner {

    private static final Money ZERO = Money.zero(CurrencyCode.BRL);

    private GoalPlanner() {
    }

    public record Track(boolean decrease, Money start, LocalDate startOn, Money target, LocalDate deadline) {
    }

    public static GoalPlan plan(Track track, Money current, LocalDate today) {
        if (track == null || track.deadline() == null) {
            return null;
        }
        boolean reached = reached(track, current);
        int monthsLeft = track.deadline().isBefore(today) ? 0 : (int) monthsBetween(today, track.deadline()) + 1;
        Money distance = reached ? ZERO : track.decrease() ? current.subtract(track.target()) : track.target().subtract(current);
        Money pace = reached ? ZERO : monthsLeft == 0 ? null : Money.ofCents(Math.ceilDiv(distance.cents(), (long) monthsLeft), CurrencyCode.BRL);
        Competence now = Competence.from(today);
        Money milestone = expectedAtEndOf(track, now);
        Money expectedNow = expectedAtEndOf(track, now.minusMonths(1));
        Money gap = track.decrease() ? expectedNow.subtract(current) : current.subtract(expectedNow);
        return new GoalPlan(pace, monthsLeft, milestone, reached || !gap.isNegative(), gap);
    }

    public static GoalConsistency consistency(Track track, Money current, LocalDate today, Function<Competence, Money> snapshotOf) {
        if (track == null || track.deadline() == null) {
            return GoalConsistency.none();
        }
        Competence now = Competence.from(today);
        List<GoalConsistency.Month> history = new ArrayList<>();
        for (Competence m = Competence.from(track.startOn()); !m.isAfter(now); m = m.plusMonths(1)) {
            boolean closed = m.isBefore(now);
            Money expected = expectedAtEndOf(track, m);
            Money actual = closed ? snapshotOf.apply(m) : current;
            Boolean met = actual == null ? null : track.decrease() ? !actual.isGreaterThan(expected) : !actual.isLessThan(expected);
            history.add(new GoalConsistency.Month(m, expected, actual, met, closed));
        }
        int streak = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            GoalConsistency.Month month = history.get(i);
            if (!month.closed()) {
                continue;
            }
            if (!Boolean.TRUE.equals(month.met())) {
                break;
            }
            streak++;
        }
        long withData = history.stream().filter(m -> m.closed() && m.met() != null).count();
        long met = history.stream().filter(m -> m.closed() && Boolean.TRUE.equals(m.met())).count();
        BigDecimal rate = withData == 0 ? null : BigDecimal.valueOf(met).divide(BigDecimal.valueOf(withData), GoalProgress.RATIO_SCALE, RoundingMode.HALF_UP);
        return new GoalConsistency(history, streak, rate);
    }

    public static Money expectedAtEndOf(Track track, Competence month) {
        YearMonth startMonth = YearMonth.from(track.startOn());
        long total = Math.max(1, ChronoUnit.MONTHS.between(startMonth, YearMonth.from(track.deadline())) + 1);
        long k = Math.max(0, Math.min(total, ChronoUnit.MONTHS.between(startMonth, month.value()) + 1));
        long delta = track.target().cents() - track.start().cents();
        BigDecimal step = BigDecimal.valueOf(delta).multiply(BigDecimal.valueOf(k)).divide(BigDecimal.valueOf(total), 0, RoundingMode.HALF_UP);
        return Money.ofCents(track.start().cents() + step.longValueExact(), CurrencyCode.BRL);
    }

    public static boolean reached(Track track, Money current) {
        return track.decrease() ? !current.isGreaterThan(track.target()) : !current.isLessThan(track.target());
    }

    private static long monthsBetween(LocalDate from, LocalDate to) {
        return ChronoUnit.MONTHS.between(YearMonth.from(from), YearMonth.from(to));
    }
}
