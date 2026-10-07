package com.moneylog.core.finance.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.FlowDirection;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

/**
 * Projeção determinística (D1 §2.8, PR 00 §19, S-152, S-155): série mensal de {@code em contas + entradas previstas − saídas previstas}
 * até o horizonte, vinda <b>só</b> dos compromissos ({@link CommitmentSchedule}: faturas com as parcelas aninhadas, dívidas datadas e
 * recorrências pendentes), <b>sempre</b> rotulada como projeção e com as premissas. Cada compromisso entra uma vez só.
 *
 * <ul>
 *   <li>Um ponto por mês-calendário, do mês de hoje ao mês do fim do horizonte. O 1º ponto traz os vencidos e o resto do mês corrente;
 *       o último vai até o fim do horizonte ({@code periodEnd}), então o {@code balance} dele é igual ao {@code projected} do dashboard
 *       para o mesmo horizonte (a mesma função: {@code D30} = {@code projected.d30}).</li>
 *   <li>{@code balance} = em contas + Σ entradas acumuladas − Σ saídas acumuladas até o fim do ponto.</li>
 *   <li>Meta e reserva são informação ({@code goals}): reserva não é saída e não muda a série.</li>
 *   <li>A tendência histórica ({@code trend}) é opcional, separada e rotulada estimativa; a série base não muda com ela.</li>
 * </ul>
 */
public record Projection(Horizon horizon, LocalDate asOf, boolean isProjection, Money startingBalance, List<Point> series,
                         List<String> assumptions, Goals goals, Trend trend) {

    /** Chaves traduzíveis das premissas (sem texto fixo). */
    public static final List<String> ASSUMPTIONS = List.of("ACTIVE_RECURRENCES", "REGISTERED_INVOICES_AND_INSTALLMENTS",
            "DEBTS_WITH_EXPECTED_DATE", "NO_NEW_SPENDING_TREND");

    /** Horizontes aceitos (S-155). */
    public static final List<Horizon> HORIZONS = DashboardSummary.PROJECTION_HORIZONS;

    public Projection {
        series = List.copyOf(series);
        assumptions = List.copyOf(assumptions);
    }

    /**
     * @param periodEnd último dia coberto pelo ponto (o último ponto termina no fim do horizonte)
     * @param inflows   entradas previstas no período
     * @param outflows  saídas previstas no período
     */
    public record Point(Competence competence, LocalDate periodEnd, Money inflows, Money outflows, Money balance) {
    }

    public record GoalInfo(UUID goalId, String name, Money current, Money target) {
    }

    /** O que está reservado em metas (informativo; não é saída). */
    public record Goals(Money reserved, List<GoalInfo> items) {
        public Goals {
            items = List.copyOf(items);
        }
    }

    /** Ponto da tendência: gasto novo estimado acumulado e o saldo da base menos esse gasto. */
    public record TrendPoint(Competence competence, Money estimatedSpending, Money balance) {
    }

    /** Tendência opcional (S-152): sempre {@code estimate = true}. */
    public record Trend(boolean available, String reason, Money monthlyAverage, List<Competence> basedOn, List<TrendPoint> series) {
        public Trend {
            basedOn = List.copyOf(basedOn);
            series = List.copyOf(series);
        }

        public boolean estimate() {
            return true;
        }
    }

    public static Projection of(FinancialRecords records, Collection<RecurringTransaction> recurrences, Horizon horizon, LocalDate today,
                                boolean includeTrend) {
        if (!HORIZONS.contains(horizon)) {
            throw new IllegalArgumentException("horizonte da projeção: " + horizon);
        }
        Ledger ledger = records.ledger();
        CashPosition cash = CashPosition.at(records, ledger, today);
        CommitmentSchedule schedule = CommitmentSchedule.of(records, recurrences, today);
        LocalDate end = horizon.endFrom(today);
        Competence first = Competence.from(today);
        Competence last = Competence.from(end);
        Money zero = Money.zero(CurrencyCode.BRL);
        Money running = cash.inAccounts();
        List<Point> series = new ArrayList<>();
        for (Competence month = first; !month.isAfter(last); month = month.plusMonths(1)) {
            LocalDate from = month.equals(first) ? LocalDate.MIN : month.firstDay();
            LocalDate to = month.lastDay().isAfter(end) ? end : month.lastDay();
            Money in = zero;
            Money out = zero;
            for (Commitment c : schedule.between(from, to)) {
                if (c.direction() == FlowDirection.IN) {
                    in = in.add(c.amount());
                } else {
                    out = out.add(c.amount());
                }
            }
            running = running.add(in).subtract(out);
            series.add(new Point(month, to, in, out, running));
        }
        return new Projection(horizon, today, true, cash.inAccounts(), series, ASSUMPTIONS, goals(records, cash, today),
                includeTrend ? trend(records, ledger, today, series) : null);
    }

    private static Goals goals(FinancialRecords records, CashPosition cash, LocalDate today) {
        List<GoalInfo> items = records.goals().stream().filter(g -> !g.isArchived())
                .map((Goal g) -> new GoalInfo(g.id(), g.name(), g.current(today), g.target())).toList();
        return new Goals(GoalReserves.at(records, cash).inCash(), items);
    }

    private static Trend trend(FinancialRecords records, Ledger ledger, LocalDate today, List<Point> base) {
        SpendingTrend trend = SpendingTrend.of(records, ledger, today);
        if (!trend.available()) {
            return new Trend(false, trend.reason(), null, List.of(), List.of());
        }
        Competence current = Competence.from(today);
        List<TrendPoint> points = new ArrayList<>();
        for (Point point : base) {
            // meses depois do corrente (o corrente já está em andamento); mês parcial no fim do horizonte conta inteiro (conservador)
            long ahead = java.time.temporal.ChronoUnit.MONTHS.between(current.value(), point.competence().value());
            Money spending = trend.average().times(ahead);
            points.add(new TrendPoint(point.competence(), spending, point.balance().subtract(spending)));
        }
        return new Trend(true, null, trend.average(), trend.months(), points);
    }
}
