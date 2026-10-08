package com.moneylog.core.goal.domain;

import java.math.BigDecimal;
import java.util.List;

import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

/**
 * Histórico mensal de cumprimento do ritmo (ADR-016). Mês sem instantâneo é "sem dado" ({@code met} e {@code actual} nulos), nunca
 * "cumprido".
 *
 * @param streak meses fechados seguidos (do mais recente para trás) cumprindo o ritmo
 * @param rate   cumpridos ÷ meses fechados com dado (4 casas); {@code null} sem nenhum mês fechado com dado
 */
public record GoalConsistency(List<Month> history, int streak, BigDecimal rate) {

    public GoalConsistency {
        history = List.copyOf(history);
    }

    public static GoalConsistency none() {
        return new GoalConsistency(List.of(), 0, null);
    }

    /**
     * @param expected valor esperado no fim do mês na trajetória
     * @param actual   valor da medida no fim do mês (no mês corrente, o valor de agora); {@code null} = sem dado
     * @param met      {@code null} = sem dado
     * @param closed   o mês já terminou (só os fechados contam em {@code streak} e {@code rate})
     */
    public record Month(Competence month, Money expected, Money actual, Boolean met, boolean closed) {
    }
}
