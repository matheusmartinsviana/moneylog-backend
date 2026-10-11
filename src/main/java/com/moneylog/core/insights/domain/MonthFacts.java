package com.moneylog.core.insights.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

/**
 * Tudo que os insights mensais leem, já calculado e montado uma vez por requisição ({@code InsightContext}). Os calculadores são funções
 * puras sobre este registro: nenhum acessa gateway, relógio nem razão (ADR-008, ADR-019).
 *
 * @param today          data de referência (relógio no fuso do usuário)
 * @param history        meses fechados <b>com dado</b> antes de {@code month}, em ordem crescente, no máximo 6
 * @param budget         base do dinheiro livre e do orçamento do dia; {@code null} fora do mês corrente
 * @param dailyVariable  gasto variável (fora recorrência, parcela e dívida) de cada dia decorrido, do dia 1 até hoje; vazio fora do corrente
 * @param recurringOut   recorrências de saída do mês (realizadas + ocorrências ainda previstas)
 * @param installments   parcelas com competência no mês
 * @param debtPaymentsDue pagamentos de dívida a vencer no mês
 * @param debtTotal      total de dívidas (ADR-013)
 * @param cashBalance    saldo líquido em contas (sem cartão)
 * @param installmentLoad parcelas por mês, de {@code month} até {@code month + 11}
 * @param cardOutstanding fatura com saldo que vence no mês
 */
public record MonthFacts(LocalDate today, Competence month, MonthSnapshot current, List<MonthSnapshot> history, Budget budget,
                         List<Money> dailyVariable, Money recurringOut, Money installments, Money debtPaymentsDue, Money debtTotal,
                         Money cashBalance, boolean hasCashAccounts, List<Money> installmentLoad, List<GoalFact> goals,
                         List<CardFact> cards, Money cardOutstanding, Map<UUID, String> categoryNames,
                         List<SubscriptionDetector.Line> subscriptionLines, Set<String> registeredDescriptions) {

    public MonthFacts {
        Objects.requireNonNull(today, "today");
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(current, "current");
        history = List.copyOf(history);
        dailyVariable = List.copyOf(dailyVariable);
        installmentLoad = List.copyOf(installmentLoad);
        goals = List.copyOf(goals);
        cards = List.copyOf(cards);
        categoryNames = Map.copyOf(categoryNames);
        subscriptionLines = List.copyOf(subscriptionLines);
        registeredDescriptions = Set.copyOf(registeredDescriptions);
    }

    /**
     * Base do dinheiro livre (competência, sem dupla contagem): renda <b>recebida</b> no mês menos o consumo já feito, menos o que já é
     * conhecido e ainda não aconteceu (lançamentos com data depois de hoje e ocorrências de recorrência ainda previstas), menos o reservado
     * em metas no mês. Fatura de cartão nunca entra: as compras já são consumo (pagar a fatura não é despesa nova, PR 01 regra 3).
     */
    public record Budget(Money received, Money spentSoFar, Money laterKnown, Money pendingRecurring, Money reserved) {

        public Money upcoming() {
            return laterKnown.add(pendingRecurring);
        }

        public Money free() {
            return received.subtract(spentSoFar).subtract(upcoming()).subtract(reserved);
        }
    }

    /** Ritmo de uma meta, exatamente como a API de metas calcula (ADR-016); nada é recalculado aqui. */
    public record GoalFact(UUID goalId, String name, boolean hasPlan, boolean onTrack, Money requiredPace, Money gap, int monthsLeft,
                           int streak) {
    }

    /** Uso do limite de um cartão ({@code limit} nulo = sem limite cadastrado: não há uso a calcular). */
    public record CardFact(UUID cardId, String name, Money limit, Money used) {
    }

    public boolean currentMonth() {
        return month.equals(Competence.from(today));
    }

    public boolean futureMonth() {
        return month.isAfter(Competence.from(today));
    }

    public int daysInMonth() {
        return month.value().lengthOfMonth();
    }

    /** Dias decorridos do mês, contando hoje (mês passado = todos; futuro = 0). */
    public int daysElapsed() {
        if (futureMonth()) {
            return 0;
        }
        return currentMonth() ? today.getDayOfMonth() : daysInMonth();
    }
}
