package com.moneylog.core.insights.domain;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

/**
 * Um mês já agregado pelas calculadoras do razão ({@code EntryReport}, a mesma fonte do dashboard e da lista, PR 01 regra 10).
 *
 * @param income      renda por competência (ADR-017)
 * @param consumption consumo líquido (despesa de verdade; reembolso abate; sem transferência, investimento nem empréstimo)
 * @param essential   consumo essencial = recorrências + parcelas + dívidas (fontes já estruturadas), sem categorizar tudo
 * @param byCategory  consumo líquido por categoria (pode ter valor negativo por reembolso)
 */
public record MonthSnapshot(Competence month, Money income, Money consumption, Money essential, Map<UUID, Money> byCategory) {

    public MonthSnapshot {
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(income, "income");
        Objects.requireNonNull(consumption, "consumption");
        Objects.requireNonNull(essential, "essential");
        byCategory = Map.copyOf(byCategory);
    }

    /** Mês sem renda e sem consumo: não conta como histórico (usuário ainda não existia ou não usou o app). */
    public boolean isEmpty() {
        return income.isZero() && consumption.isZero();
    }

    /** Consumo da categoria limitado a zero (reembolso que a leva abaixo de zero não vira "queda"). */
    public Money categoryFloorZero(UUID categoryId) {
        Money value = byCategory.get(categoryId);
        return value == null || !value.isPositive() ? Money.zero(consumption.currency()) : value;
    }
}
