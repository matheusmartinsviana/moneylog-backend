package com.moneylog.core.goal.domain;

import com.moneylog.core.sk.money.Money;

/**
 * Plano automático (ADR-016), calculado pelo sistema quando há prazo e alvo. Trajetória linear do valor inicial ao alvo, um passo por
 * mês-calendário, do mês de início ao mês do prazo (inclusive).
 *
 * @param requiredPace  ritmo necessário por mês para chegar no prazo (distância que falta ÷ meses restantes, para cima ao centavo);
 *                      0,00 se atingida; {@code null} com prazo vencido (sem divisão por zero)
 * @param monthsLeft    meses-calendário do mês de hoje ao mês do prazo, inclusive; 0 com prazo vencido
 * @param nextMilestone valor esperado no fim do mês atual na trajetória
 * @param onTrack       a medida está melhor ou igual à trajetória esperada até o fim do mês passado (ou já foi atingida)
 * @param gap           diferença para a trajetória: positivo = adiantado, negativo = atrasado
 */
public record GoalPlan(Money requiredPace, int monthsLeft, Money nextMilestone, boolean onTrack, Money gap) {
}
