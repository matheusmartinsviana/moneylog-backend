package com.moneylog.core.goal.domain;

/**
 * Medida do sistema que uma meta {@link GoalKind#METRIC} acompanha (ADR-016). Cada uma tem <b>uma única fonte</b>, a mesma das telas
 * (nenhuma regra é recalculada para a meta):
 *
 * <ul>
 *   <li>{@code TOTAL_DEBT}, {@code CARD_DEBT}, {@code PEOPLE_DEBT}, {@code OTHER_DEBT}: {@code total} e {@code parts} de
 *       {@code GET /v1/debts/overview} (saldo devedor lido do estado; sem dupla contagem);</li>
 *   <li>{@code ACCOUNTS_BALANCE}, {@code AVAILABLE_BALANCE}: {@code balances.inAccounts} e {@code balances.available} do dashboard;</li>
 *   <li>{@code FIXED_COSTS_MONTHLY}: {@code fixedCosts} de {@code GET /v1/recurrences/summary} (estimativa mensal).</li>
 * </ul>
 * Falta de dado não é erro: a medida vale 0,00 (ex.: sem cartões, {@code CARD_DEBT} = 0,00).
 */
public enum GoalMetric {
    TOTAL_DEBT,
    CARD_DEBT,
    PEOPLE_DEBT,
    OTHER_DEBT,
    ACCOUNTS_BALANCE,
    AVAILABLE_BALANCE,
    FIXED_COSTS_MONTHLY;

    /** Vem da visão de dívidas (não precisa de recorrências nem do dashboard). */
    public boolean isDebt() {
        return this == TOTAL_DEBT || this == CARD_DEBT || this == PEOPLE_DEBT || this == OTHER_DEBT;
    }

    /** Vem do {@code balances} do dashboard. */
    public boolean isBalance() {
        return this == ACCOUNTS_BALANCE || this == AVAILABLE_BALANCE;
    }
}
