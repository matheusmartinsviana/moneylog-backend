package com.moneylog.core.finance.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.CreditCardInvoice;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.card.domain.InvoiceStatus;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.CardInvoices;

public record MonthlySummary(Competence competence, boolean partial, boolean isProjection, Money income, Money expenses,
                             Money investments, Money net, BigDecimal savingsRate, LargestCategory largestCategory,
                             LargestExpense largestExpense, CardSummary card, Money futureInstallments, Comparison comparison) {

    public static final int RATIO_SCALE = 4;

    public record LargestCategory(UUID categoryId, Money amount) {
    }

    public record LargestExpense(UUID transactionId, UUID debtId, Integer installmentNumber, Integer installmentTotal, UUID categoryId,
                                 LocalDate date, Money amount, String description) {
    }

    public record CardSummary(Money consumption, Money outstanding) {
    }

    public record Delta(Money amount, BigDecimal percent) {

        static Delta between(Money current, Money previous) {
            Money difference = current.subtract(previous);
            BigDecimal percent = previous.isZero() ? null : ratio(difference, previous.abs());
            return new Delta(difference, percent);
        }
    }

    public record Totals(Money income, Money expenses, Money investments, Money net, BigDecimal savingsRate) {
    }

    public record Comparison(Competence previous, Totals previousTotals, Delta income, Delta expenses, Delta investments, Delta net,
                             BigDecimal savingsRateDelta) {
    }

    public static MonthlySummary of(FinancialRecords records, Ledger ledger, Competence month, LocalDate today) {
        Competence current = Competence.from(today);
        ConsumptionReport report = ConsumptionReport.of(records, ledger, month);
        Competence previousMonth = month.minusMonths(1);
        ConsumptionReport previous = ConsumptionReport.of(records, ledger, previousMonth);
        EntryReport consumption = EntryReport.of(records, ledger, EntryLens.CONSUMPTION, month);
        Money zero = Money.zero(CurrencyCode.BRL);
        BigDecimal savingsRate = report.income().isZero() ? null : ratio(report.net(), report.income());
        BigDecimal previousSavingsRate = previous.income().isZero() ? null : ratio(previous.net(), previous.income());
        Totals previousTotals = new Totals(previous.income(), previous.consumption(), previous.investedNet(), previous.net(),
                previousSavingsRate);
        BigDecimal savingsRateDelta = savingsRate == null || previousSavingsRate == null ? null : savingsRate.subtract(previousSavingsRate);
        Comparison comparison = new Comparison(previousMonth, previousTotals, Delta.between(report.income(), previous.income()),
                Delta.between(report.consumption(), previous.consumption()), Delta.between(report.investedNet(), previous.investedNet()),
                Delta.between(report.net(), previous.net()), savingsRateDelta);
        Money futureInstallments = ledger.sum(e -> e.posting().bookType() == BookType.EXPENSE && e.source().installmentId() != null
                && e.posting().competence().isAfter(month));
        return new MonthlySummary(month, !month.isBefore(current), month.isAfter(current), report.income(), report.consumption(),
                report.investedNet(), report.net(), savingsRate, largestCategory(consumption), largestExpense(consumption),
                new CardSummary(report.credit(), cardOutstanding(records, month, today, zero)), futureInstallments, comparison);
    }

    private static LargestCategory largestCategory(EntryReport consumption) {
        return consumption.byCategory().entrySet().stream().filter(e -> e.getValue().isPositive())
                .max(Map.Entry.<UUID, Money>comparingByValue().thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                .map(e -> new LargestCategory(e.getKey(), e.getValue())).orElse(null);
    }

    private static LargestExpense largestExpense(EntryReport consumption) {
        return consumption.lines().stream().filter(l -> l.amount().isPositive())
                .max(Comparator.comparing((EntryReport.Line l) -> l.amount())
                        .thenComparing(EntryReport.Line::date, Comparator.reverseOrder())
                        .thenComparing(l -> l.installmentNumber() == null ? 0 : -l.installmentNumber())
                        .thenComparing(l -> String.valueOf(l.transactionId() == null ? l.debtId() : l.transactionId()),
                                Comparator.reverseOrder()))
                .map(l -> new LargestExpense(l.transactionId(), l.debtId(), l.installmentNumber(), l.installmentTotal(), l.categoryId(),
                        l.date(), l.amount(), l.description()))
                .orElse(null);
    }

    private static Money cardOutstanding(FinancialRecords records, Competence month, LocalDate today, Money zero) {
        Money total = zero;
        for (CreditCard card : records.cards()) {
            InvoiceChain chain = CardInvoices.chainFor(card, records.transactions());
            for (CreditCardInvoice invoice : chain.invoices()) {
                if (month.contains(invoice.dueDate()) && invoice.statusAt(today) != InvoiceStatus.PAID && invoice.outstanding().isPositive()) {
                    total = total.add(invoice.outstanding());
                }
            }
        }
        return total;
    }

    static BigDecimal ratio(Money numerator, Money denominator) {
        return BigDecimal.valueOf(numerator.cents()).divide(BigDecimal.valueOf(denominator.cents()), RATIO_SCALE, RoundingMode.HALF_UP);
    }
}
