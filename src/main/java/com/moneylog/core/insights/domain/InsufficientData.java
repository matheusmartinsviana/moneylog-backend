package com.moneylog.core.insights.domain;

import java.util.Objects;

/**
 * Por que um insight não saiu (nunca se inventa número com dado insuficiente, ADR-019).
 *
 * @param type    o tipo que deixou de sair ({@code ALL} quando o mês inteiro não se aplica)
 * @param missing quantos meses (ou dias) faltam para emitir, quando faz sentido; {@code null} nos demais
 */
public record InsufficientData(String type, Reason reason, Integer missing) {

    public InsufficientData {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(reason, "reason");
    }

    public enum Reason {
        /** Poucos meses fechados com dado. */
        HISTORY,
        /** Sem renda no mês (ou sem renda recebida, para o orçamento do dia). */
        NO_INCOME,
        /** Mês corrente ainda curto demais para extrapolar. */
        PARTIAL_MONTH,
        /** Sem o dado de base (sem parcelas, sem consumo essencial, sem plano). */
        NO_DATA,
        /** Sem referência para comparar (média zero). */
        NO_BASELINE,
        NO_ACCOUNTS,
        NO_SCHEDULE,
        NO_LIMIT,
        NO_PLAN,
        /** Mês futuro: só há o que já está agendado. */
        FUTURE_MONTH
    }
}
