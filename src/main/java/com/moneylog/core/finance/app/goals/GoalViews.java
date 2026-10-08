package com.moneylog.core.finance.app.goals;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.GoalReserves;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.goal.domain.GoalConsistency;
import com.moneylog.core.goal.domain.GoalMetric;
import com.moneylog.core.goal.domain.GoalPlan;
import com.moneylog.core.goal.domain.GoalPlanner;
import com.moneylog.core.goal.domain.GoalProgress;
import com.moneylog.core.goal.domain.GoalReservation;
import com.moneylog.core.goal.domain.GoalStage;
import com.moneylog.core.goal.domain.GoalStatus;
import com.moneylog.core.goal.domain.MetricProgress;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.Competence;

/**
 * Monta {@link GoalDetails} sobre os {@link FinancialRecords} carregados uma vez (S-79) e as medidas já calculadas uma vez por
 * requisição ({@link GoalReadModel}): a reserva descoberta usa o mesmo {@code GoalReserves} do dashboard e da conta.
 */
public final class GoalViews {

    private static final Money ZERO = Money.zero(CurrencyCode.BRL);

    private GoalViews() {
    }

    /**
     * Tudo que a montagem das metas precisa, calculado uma vez para a requisição inteira.
     *
     * @param reserves  {@code null} se nenhuma meta é de guardar dinheiro
     * @param metrics   valor atual das medidas necessárias (inclui {@code TOTAL_DEBT} se houver meta de quitar dívidas)
     * @param snapshots instantâneos mensais por meta
     */
    public record Context(Map<UUID, String> accountNames, GoalReserves reserves, Map<GoalMetric, Money> metrics,
                          Map<UUID, Map<Competence, Money>> snapshots, LocalDate today, ZoneId zone) {
    }

    /** Nomes das contas por id (para as reservas). */
    public static Map<UUID, String> accountNames(FinancialRecords records) {
        Map<UUID, String> names = new HashMap<>();
        for (Account account : records.accounts()) {
            names.put(account.id(), account.name());
        }
        return names;
    }

    public static GoalDetails of(Versioned<Goal> versioned, Context c) {
        Goal goal = versioned.value();
        return switch (goal.kind()) {
            case DEBT_PAYOFF -> payoff(versioned, c);
            case METRIC -> metric(versioned, c);
            case SAVINGS -> savings(versioned, c);
        };
    }

    private static GoalDetails payoff(Versioned<Goal> versioned, Context c) {
        Goal goal = versioned.value();
        Money total = c.metrics().getOrDefault(GoalMetric.TOTAL_DEBT, ZERO);
        GoalDetails.PayoffInfo info = new GoalDetails.PayoffInfo(goal.isBaselineSet(), goal.baseline(), goal.baselineAt(),
                goal.grewSinceBaseline(total), total);
        GoalStatus status = goal.payoffStatus(total);
        return new GoalDetails(goal, versioned.version(), versioned.createdAt(), versioned.updatedAt(), c.today(), goal.payoffProgress(total),
                status, goal.payoffMonthlyNeeded(total, c.today()), false, List.of(), info, null,
                planning(versioned, c, total, status == GoalStatus.ACHIEVED));
    }

    private static GoalDetails metric(Versioned<Goal> versioned, Context c) {
        Goal goal = versioned.value();
        if (goal.isArchived()) {
            // meta arquivada não calcula: sem leitura da medida, sem consistência
            GoalProgress frozen = new GoalProgress(ZERO, goal.target(), java.math.BigDecimal.ZERO.setScale(GoalProgress.RATIO_SCALE));
            return new GoalDetails(goal, versioned.version(), versioned.createdAt(), versioned.updatedAt(), c.today(), frozen,
                    GoalStatus.ARCHIVED, null, false, List.of(), null, new GoalDetails.MetricInfo(null, null, false, null),
                    planning(versioned, c, null, false));
        }
        Money current = c.metrics().getOrDefault(goal.metric(), ZERO);
        MetricProgress progress = goal.metricProgress(current);
        GoalStatus status = goal.metricStatus(progress);
        return new GoalDetails(goal, versioned.version(), versioned.createdAt(), versioned.updatedAt(), c.today(),
                new GoalProgress(current, goal.target(), progress.ratio()), status, goal.metricMonthlyNeeded(progress, c.today()), false,
                List.of(), null, new GoalDetails.MetricInfo(current, progress.remaining(), progress.reached(), c.today()),
                planning(versioned, c, current, progress.reached()));
    }

    private static GoalDetails savings(Versioned<Goal> versioned, Context c) {
        Goal goal = versioned.value();
        List<GoalDetails.ReservationView> views = goal.reservations().stream()
                .sorted(Comparator.comparing(GoalReservation::date).thenComparing(GoalReservation::createdAt).reversed())
                .map(r -> new GoalDetails.ReservationView(r, c.accountNames().getOrDefault(r.accountId(), null))).toList();
        GoalStatus status = goal.status(c.today());
        return new GoalDetails(goal, versioned.version(), versioned.createdAt(), versioned.updatedAt(), c.today(), goal.progress(c.today()),
                status, goal.monthlyNeeded(c.today()), goal.isUncovered(c.reserves()::isUncovered, c.today()), views, null, null,
                planning(versioned, c, goal.current(c.today()), status == GoalStatus.ACHIEVED));
    }

    /**
     * Estágio, plano automático, declarado e consistência (ADR-016). {@code current} nulo = meta que não calcula (arquivada): o estágio
     * sai só de prazo e plano declarado.
     */
    private static GoalDetails.Planning planning(Versioned<Goal> versioned, Context c, Money current, boolean achieved) {
        Goal goal = versioned.value();
        GoalPlanner.Track track = goal.track(versioned.createdAt().atZone(c.zone()).toLocalDate(), c.zone());
        GoalPlan plan = null;
        GoalConsistency consistency = GoalConsistency.none();
        if (current != null && track != null) {
            plan = GoalPlanner.plan(track, current, c.today());
            Map<Competence, Money> stored = c.snapshots().getOrDefault(goal.id(), Map.of());
            consistency = GoalPlanner.consistency(track, current, c.today(),
                    goal.kind() == com.moneylog.core.goal.domain.GoalKind.SAVINGS ? m -> goal.current(m.lastDay()) : stored::get);
        }
        GoalStage stage = GoalStage.derive(achieved, goal.deadline() != null, goal.hasAutoPlan(), goal.hasDeclaredPlan(), consistency.streak());
        return new GoalDetails.Planning(stage, plan, goal.planNote(), goal.actions(), consistency);
    }
}
