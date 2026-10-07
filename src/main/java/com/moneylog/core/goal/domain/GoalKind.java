package com.moneylog.core.goal.domain;

/**
 * Tipo da meta (ADR-013). {@code SAVINGS} é a meta de guardar dinheiro por reserva lógica (S-147), o padrão de toda meta existente.
 * {@code DEBT_PAYOFF} é "quitar todas as dívidas": não tem reservas, o progresso vem do total de dívidas ({@code DebtOverview}) em
 * relação ao {@code baseline} que o usuário confirma.
 */
public enum GoalKind {
    SAVINGS,
    DEBT_PAYOFF
}
