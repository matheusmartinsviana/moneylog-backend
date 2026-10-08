package com.moneylog.core.goal.domain;

import com.moneylog.core.sk.money.Money;

/** Sentido da meta {@link GoalKind#METRIC}, derivado de {@code target} × {@code baseline} (nunca informado). */
public enum GoalDirection {
    /** A medida precisa cair até o alvo (dívida, custo fixo). */
    DECREASE,
    /** A medida precisa subir até o alvo (saldo). */
    INCREASE;

    /** {@code null} quando {@code target == baseline} (não há caminho a percorrer). */
    public static GoalDirection derive(Money target, Money baseline) {
        int cmp = target.compareTo(baseline);
        return cmp == 0 ? null : cmp < 0 ? DECREASE : INCREASE;
    }
}
