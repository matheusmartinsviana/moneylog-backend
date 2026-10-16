package com.moneylog.core.purchasegroup.app;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.card.domain.InvoiceStatus;
import com.moneylog.core.card.domain.TransactionInstallment;
import com.moneylog.core.finance.app.cards.CardViews;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionType;

public record PurchasePlan(Transaction purchase, String cardName, int totalInstallments, Money total, List<Line> lines) {

    public record Line(int number, Money amount, Money mine, LocalDate dueDate, boolean pending) {

        public YearMonth dueMonth() {
            return YearMonth.from(dueDate);
        }
    }

    public static final int MIN_INSTALLMENTS = 2;

    public PurchasePlan {
        lines = List.copyOf(lines);
    }

    public static boolean isInstallmentPurchase(Transaction t) {
        return t.type() == TransactionType.CARD_PURCHASE && t.isActive() && t.cardId() != null
                && t.installments().stream().anyMatch(i -> i.total() >= MIN_INSTALLMENTS);
    }

    public static List<PurchasePlan> allOf(FinancialRecords records, LocalDate today) {
        Map<UUID, InvoiceChain> chains = new HashMap<>();
        List<PurchasePlan> plans = new ArrayList<>();
        for (Transaction t : records.transactions()) {
            if (isInstallmentPurchase(t)) {
                plans.add(of(t, records, chains, today));
            }
        }
        return plans;
    }

    public static PurchasePlan of(Transaction purchase, FinancialRecords records, Map<UUID, InvoiceChain> chains, LocalDate today) {
        CreditCard card = records.cards().stream().filter(c -> c.id().equals(purchase.cardId())).findFirst().orElseThrow();
        InvoiceChain chain = chains.computeIfAbsent(card.id(), id -> CardViews.chainOf(card, records));
        Money zero = Money.zero(CurrencyCode.BRL);
        List<TransactionInstallment> stored = purchase.installments().stream()
                .sorted(Comparator.comparingInt(TransactionInstallment::number)).toList();
        List<Line> lines = new ArrayList<>();
        Money charged = zero;
        for (TransactionInstallment i : stored) {
            if (i.isCanceled()) {
                continue;
            }
            InvoiceStatus status = chain.statusOf(i.invoiceClosingDate(), today);
            Money mine = purchase.purchaseShares() == null ? i.amount() : purchase.purchaseShares().split(i.amount()).mine();
            lines.add(new Line(i.number(), i.amount(), mine, chain.invoice(i.invoiceClosingDate()).dueDate(), status != InvoiceStatus.PAID));
            charged = charged.add(i.amount());
        }
        int totalInstallments = stored.isEmpty() ? 0 : stored.get(0).total();
        Money past = stored.isEmpty() ? zero : stored.get(0).amount().times(stored.get(0).number() - 1L);
        return new PurchasePlan(purchase, card.name(), totalInstallments,
                purchase.isActive() ? charged.add(past) : zero, lines);
    }

    public List<Line> pendingLines() {
        return lines.stream().filter(Line::pending).toList();
    }

    public Money remaining() {
        return Money.sum(pendingLines().stream().map(Line::amount).toList(), CurrencyCode.BRL);
    }

    public Money myRemaining() {
        return Money.sum(pendingLines().stream().map(Line::mine).toList(), CurrencyCode.BRL);
    }

    public LocalDate lastDueDate() {
        return lines.stream().map(Line::dueDate).max(Comparator.naturalOrder()).orElse(null);
    }
}
