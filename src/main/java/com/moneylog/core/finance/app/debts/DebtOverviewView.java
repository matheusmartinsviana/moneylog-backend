package com.moneylog.core.finance.app.debts;

import java.math.BigDecimal;
import java.util.UUID;

import com.moneylog.core.finance.domain.DebtOverview;
import com.moneylog.core.sk.money.Money;

/**
 * A visão total de dívidas como a API mostra: o cálculo ({@link DebtOverview}) mais a meta de quitação ativa, se houver. O progresso
 * da meta sai do <b>mesmo</b> total (um número só).
 *
 * @param payoffGoal a meta {@code DEBT_PAYOFF} ativa; {@code null} sem meta
 */
public record DebtOverviewView(DebtOverview overview, PayoffGoalRef payoffGoal) {

    /** @param progress razão com 4 casas ({@code 0.0000} com {@code baselineSet = false}) */
    public record PayoffGoalRef(UUID goalId, boolean baselineSet, BigDecimal progress, Money remaining) {
    }
}
