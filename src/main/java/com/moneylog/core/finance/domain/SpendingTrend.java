package com.moneylog.core.finance.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionSource;
import com.moneylog.core.transaction.domain.TransactionType;

public record SpendingTrend(boolean available, String reason, Money average, List<Competence> months) {

    public static final String INSUFFICIENT_HISTORY = "INSUFFICIENT_HISTORY";
    public static final int WINDOW_MONTHS = 3;

    public static SpendingTrend of(FinancialRecords records, Ledger ledger, LocalDate today) {
        Competence current = Competence.from(today);
        Competence oldest = current.minusMonths(WINDOW_MONTHS);
        LocalDate earliest = records.transactions().stream().filter(Transaction::isActive).map(Transaction::date)
                .min(LocalDate::compareTo).orElse(null);
        if (earliest == null || earliest.isAfter(oldest.firstDay())) {
            return new SpendingTrend(false, INSUFFICIENT_HISTORY, null, List.of());
        }
        Map<UUID, Transaction> transactions = records.transactionsById();
        List<Competence> months = List.of(oldest, oldest.plusMonths(1), oldest.plusMonths(2));
        Money total = Money.zero(CurrencyCode.BRL);
        for (Competence month : months) {
            int entries = 0;
            for (LedgerEntry e : ledger.inCompetence(BookType.EXPENSE, month)) {
                Transaction tx = transactions.get(e.source().transactionId());
                if (tx != null && isNewConsumption(tx, e)) {
                    total = total.add(e.posting().amount());
                    entries++;
                }
            }
            if (entries == 0) {
                return new SpendingTrend(false, INSUFFICIENT_HISTORY, null, List.of());
            }
        }
        long cents = BigDecimal.valueOf(total.cents()).divide(BigDecimal.valueOf(WINDOW_MONTHS), 0, RoundingMode.HALF_UP).longValueExact();
        return new SpendingTrend(true, null, Money.ofCents(cents, CurrencyCode.BRL), months);
    }

    private static boolean isNewConsumption(Transaction tx, LedgerEntry entry) {
        if (tx.origin().source() == TransactionSource.RECURRENCE) {
            return false;
        }
        if (tx.type() == TransactionType.EXPENSE) {
            return true;
        }
        if (tx.type() != TransactionType.CARD_PURCHASE) {
            return false;
        }
        UUID installmentId = entry.source().installmentId();
        return installmentId == null || tx.installments().stream()
                .anyMatch(i -> i.id().equals(installmentId) && i.number() == 1);
    }
}
