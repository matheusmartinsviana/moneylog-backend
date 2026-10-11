package com.moneylog.core.finance.domain;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.Transaction;

public record CashFlowReport(LocalDate from, LocalDate to, Money in, Money out, Money net, int count) {

    public static CashFlowReport forCompetence(FinancialRecords records, Ledger ledger, Competence competence) {
        return between(records, ledger, competence.firstDay(), competence.lastDay());
    }

    public static CashFlowReport between(FinancialRecords records, Ledger ledger, LocalDate from, LocalDate to) {
        return between(records, ledger, from, to, null);
    }

    public static CashFlowReport between(FinancialRecords records, Ledger ledger, LocalDate from, LocalDate to,
                                         Predicate<Transaction> filter) {
        List<UUID> cash = records.cashAccountIds();
        return of(records, ledger, from, to, filter,
                e -> e.posting().bookType() == BookType.ACCOUNT && cash.contains(e.posting().book().id()));
    }

    public static CashFlowReport forAccount(FinancialRecords records, Ledger ledger, UUID accountId, LocalDate from, LocalDate to,
                                            Predicate<Transaction> filter) {
        Objects.requireNonNull(accountId, "accountId");
        return of(records, ledger, from, to, filter,
                e -> e.posting().bookType() == BookType.ACCOUNT && accountId.equals(e.posting().book().id()));
    }

    private static CashFlowReport of(FinancialRecords records, Ledger ledger, LocalDate from, LocalDate to,
                                     Predicate<Transaction> filter, Predicate<LedgerEntry> book) {
        Map<UUID, Transaction> transactions = filter == null ? Map.of() : records.transactionsById();
        Map<UUID, Money> byTransaction = new HashMap<>();
        for (LedgerEntry e : ledger.entries()) {
            LocalDate date = e.posting().effectiveDate();
            if (e.source().type() == PostingSourceType.TRANSACTION && book.test(e) && !date.isBefore(from) && !date.isAfter(to)
                    && accepts(filter, transactions.get(e.source().sourceId()))) {
                byTransaction.merge(e.source().sourceId(), e.posting().amount(), Money::add);
            }
        }
        Money in = Money.zero(CurrencyCode.BRL);
        Money out = Money.zero(CurrencyCode.BRL);
        int count = 0;
        for (Money effect : byTransaction.values()) {
            if (effect.isPositive()) {
                in = in.add(effect);
                count++;
            } else if (effect.isNegative()) {
                out = out.add(effect.negate());
                count++;
            }
        }
        return new CashFlowReport(from, to, in, out, in.subtract(out), count);
    }

    private static boolean accepts(Predicate<Transaction> filter, Transaction transaction) {
        return filter == null || (transaction != null && filter.test(transaction));
    }
}
