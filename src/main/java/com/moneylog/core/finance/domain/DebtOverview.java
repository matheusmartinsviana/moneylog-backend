package com.moneylog.core.finance.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

/**
 * Visão total de dívidas (ADR-013, D1 §2.11): quanto o usuário ainda deve, lido do <b>estado</b> (faturas e dívidas), nunca somando
 * movimentos. {@code total = cards + people + other}. Custo fixo e recebíveis ficam <b>fora</b> do total (grandezas diferentes). Tudo
 * calculado no backend; o front só exibe.
 */
public record DebtOverview(LocalDate asOf, Money total, Parts parts, Cards cards, DebtGroup people, DebtGroup other,
                           Money receivablesInfo, boolean hasRecords, Money fixedCostsMonthly, Income income, Liquidity liquidity,
                           Comparison comparison, Schedule schedule) {

    public record Parts(Money cards, Money people, Money other) {
    }

    /**
     * Cartões. Identidade por cartão (testada): {@code total = Σ plans.remaining + otherCharges + adjustment}, com {@code adjustment ≤ 0}
     * (pagamento parcial, crédito transportado e reembolso em fatura não paga).
     */
    public record Cards(Money total, Money invoiced, Money future, List<CardItem> items) {
    }

    public record CardItem(UUID cardId, String cardName, Money total, Money invoiced, Money future, Money otherCharges, Money adjustment,
                           List<Plan> plans) {
    }

    /**
     * Compra parcelada com parcelas ainda não pagas (não canceladas, em faturas não {@code PAID}).
     *
     * @param ongoing          nasceu como compra em andamento (cadastrada depois de já começada)
     * @param installmentAmount valor da próxima parcela
     */
    public record Plan(UUID purchaseId, String description, boolean ongoing, Money installmentAmount, int totalInstallments,
                       int remainingInstallments, int nextInstallmentNumber, Money remaining, LocalDate nextDueDate, LocalDate lastDueDate) {
    }

    public record DebtGroup(Money total, List<DebtItem> items) {
    }

    /**
     * @param personId     {@code null} na dívida com credor livre ("outras dívidas")
     * @param creditorName nome da pessoa ou do credor livre
     */
    public record DebtItem(UUID debtId, UUID personId, String creditorName, String description, Money outstanding, LocalDate expectedDate,
                           boolean overdue) {
    }

    public enum IncomeSource {
        RECURRING_ESTIMATE,
        LAST_FULL_MONTH,
        NONE
    }

    public record Income(Money monthly, IncomeSource source) {
    }

    public record Liquidity(Money inAccounts, Money available) {
    }

    public enum ComparisonReason {
        NO_INCOME
    }

    /** Razões com 4 casas (podem passar de 1); {@code null} + {@code NO_INCOME} quando a renda é zero (sem divisão por zero). */
    public record Comparison(BigDecimal debtToIncome, BigDecimal fixedCostToIncome, Money debtMinusInAccounts, ComparisonReason reason) {
    }

    public enum PayoffReason {
        UNDATED_DEBTS,
        BEYOND_HORIZON
    }

    /**
     * Cronograma contratual ("se pagar tudo no vencimento"), não previsão. Identidade testada:
     * {@code overdue + Σ points.total + undated + beyondHorizon = total}.
     */
    public record Schedule(int months, List<Point> points, Money overdue, Money undated, Money beyondHorizon, Competence payoffCompetence,
                           PayoffReason payoffReason) {
    }

    /** {@code remainingAfter = total − overdue − Σ points até este mês} (o vencido é considerado pago no 1º mês). */
    public record Point(Competence competence, Money cards, Money people, Money other, Money total, Money remainingAfter) {
    }
}
