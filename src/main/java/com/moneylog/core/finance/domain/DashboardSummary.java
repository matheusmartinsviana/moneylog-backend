package com.moneylog.core.finance.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

/**
 * Resumo do dashboard (D2 §4.4), montado só com as calculadoras de {@code core.finance}: os valores do mês são os
 * mesmos de {@link ConsumptionReport}, {@link EntryReport} e {@link CashFlowReport} para a competência de
 * {@code asOf} (PR 01 regra 10).
 *
 * <ul>
 *   <li>Disponível = em contas − comprometido em 30 dias − reservado (S-21); reservado = Σ reservas de metas em contas {@code CASH} com
 *       data ≤ {@code asOf} ({@link GoalReserves}, S-147): sem metas vale 0 e nada muda.</li>
 *   <li>Projetado(H) = em contas + entradas previstas(H) − saídas previstas(H), sempre rotulado como projeção.</li>
 *   <li>{@code month.net} = receitas − consumo (S-23).</li>
 * </ul>
 */
public record DashboardSummary(LocalDate asOf, boolean isProjection, Balances balances, Map<Horizon, Money> committed,
                               Map<Horizon, Money> projected, ConsumptionReport month, CashFlowReport cashFlow, NetWorth netWorth) {

    /** Horizontes da projeção (D2 §4.4). */
    public static final List<Horizon> PROJECTION_HORIZONS = List.of(Horizon.D30, Horizon.D90, Horizon.M6, Horizon.M12);

    /**
     * @param inAccountsChange variação do saldo em contas desde o último dia do mês anterior ({@code null} em projeção ou sem contas)
     */
    public record Balances(Money inAccounts, Money investments, Money available, Money reserved, Money committed30d,
                           InAccountsChange inAccountsChange) {

        public Balances(Money inAccounts, Money investments, Money available, Money reserved, Money committed30d) {
            this(inAccounts, investments, available, reserved, committed30d, null);
        }
    }

    /**
     * Variação de "em contas" (redesign R4, S-290): {@code previous} = {@code inAccounts} no último dia do mês anterior ({@code since}),
     * calculado pelo <b>mesmo</b> {@link CashPosition#at} do saldo de hoje (exato por data: movimentação datada depois de {@code since} não
     * entra); {@code amount} = {@code inAccounts} de {@code asOf} − {@code previous}; {@code percent} = {@code amount / |previous|} (4 casas,
     * {@code null} se {@code previous} = 0). É variação de <b>contas</b>: aplicar em investimento a reduz (o dinheiro não saiu do patrimônio).
     * O "disponível" não tem histórico (depende do comprometido, uma estimativa do futuro com o estado de hoje), por isso não há variação dele.
     */
    public record InAccountsChange(Money previous, Money amount, BigDecimal percent, LocalDate since) {
    }

    /**
     * @param asOf  data do resumo (padrão: hoje no fuso do usuário)
     * @param today hoje real; {@code asOf} depois de hoje marca o resumo como projeção
     */
    public static DashboardSummary of(FinancialRecords records, Collection<RecurringTransaction> recurrences, LocalDate asOf,
                                      LocalDate today) {
        Ledger ledger = records.ledger();
        CashPosition cash = CashPosition.at(records, ledger, asOf);
        CommitmentSchedule schedule = CommitmentSchedule.of(records, recurrences, asOf);
        Money reserved = GoalReserves.at(records, cash).inCash();
        Map<Horizon, Money> committed = new EnumMap<>(Horizon.class);
        for (Horizon h : Horizon.values()) {
            committed.put(h, schedule.committed(h));
        }
        Map<Horizon, Money> projected = new EnumMap<>(Horizon.class);
        for (Horizon h : PROJECTION_HORIZONS) {
            projected.put(h, cash.inAccounts().add(schedule.incoming(h)).subtract(schedule.committed(h)));
        }
        Money committed30 = committed.get(Horizon.D30);
        Competence competence = Competence.from(asOf);
        Balances balances = new Balances(cash.inAccounts(), cash.investments(),
                cash.inAccounts().subtract(committed30).subtract(reserved), reserved, committed30,
                inAccountsChange(records, ledger, cash, competence, asOf.isAfter(today)));
        return new DashboardSummary(asOf, asOf.isAfter(today), balances, Map.copyOf(committed), Map.copyOf(projected),
                ConsumptionReport.of(records, ledger, competence), CashFlowReport.forCompetence(records, ledger, competence),
                NetWorth.at(ledger, asOf));
    }

    private static InAccountsChange inAccountsChange(FinancialRecords records, Ledger ledger, CashPosition cash, Competence competence,
                                                     boolean projection) {
        if (projection || records.accounts().isEmpty()) {
            return null;
        }
        LocalDate since = competence.minusMonths(1).lastDay();
        Money previous = CashPosition.at(records, ledger, since).inAccounts();
        Money amount = cash.inAccounts().subtract(previous);
        return new InAccountsChange(previous, amount, previous.isZero() ? null : MonthlySummary.ratio(amount, previous.abs()), since);
    }
}
