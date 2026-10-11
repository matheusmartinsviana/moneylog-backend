package com.moneylog.core.finance.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import com.moneylog.core.card.domain.TransactionInstallment;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.Transaction;

public record EntryReport(EntryLens lens, Competence competence, List<Line> lines, Money total, Map<UUID, Money> byCategory) {

    public record Line(UUID transactionId, UUID installmentId, Integer installmentNumber, Integer installmentTotal,
                       UUID categoryId, LocalDate date, Money amount, String description, UUID debtId, Competence competence) {

        public Line(UUID transactionId, UUID installmentId, Integer installmentNumber, Integer installmentTotal,
                    UUID categoryId, LocalDate date, Money amount, String description, UUID debtId) {
            this(transactionId, installmentId, installmentNumber, installmentTotal, categoryId, date, amount, description, debtId,
                    Competence.from(date));
        }

        public Line(UUID transactionId, UUID installmentId, Integer installmentNumber, Integer installmentTotal, UUID categoryId,
                    LocalDate date, Money amount, String description) {
            this(transactionId, installmentId, installmentNumber, installmentTotal, categoryId, date, amount, description, null);
        }
    }

    public static EntryReport of(FinancialRecords records, Ledger ledger, EntryLens lens, Competence competence) {
        return of(records, ledger, lens, competence, null);
    }

    public static EntryReport of(FinancialRecords records, Ledger ledger, EntryLens lens, Competence competence,
                                 Predicate<Transaction> filter) {
        return of(records, ledger, lens, competence, filter, filter == null ? null : categoryId -> false);
    }

    public static EntryReport of(FinancialRecords records, Ledger ledger, EntryLens lens, Competence competence,
                                 Predicate<Transaction> filter, Predicate<UUID> debtCategoryFilter) {
        BookType book = lens == EntryLens.CONSUMPTION ? BookType.EXPENSE : BookType.INCOME;
        Map<UUID, Transaction> transactions = records.transactionsById();
        Map<UUID, Debt> debts = new java.util.HashMap<>();
        records.debts().forEach(debt -> debts.put(debt.id(), debt));
        List<Line> lines = new ArrayList<>();
        Map<UUID, Money> byCategory = new LinkedHashMap<>();
        Money total = Money.zero(CurrencyCode.BRL);
        List<LedgerEntry> entries = ledger.inCompetence(book, competence).stream()
                .sorted(Comparator.comparing((LedgerEntry e) -> e.posting().effectiveDate())).toList();
        for (LedgerEntry e : entries) {
            Money amount = lens == EntryLens.CONSUMPTION ? e.posting().amount() : e.posting().amount().negate();
            Transaction tx = transactions.get(e.source().transactionId());
            boolean ofDebt = e.source().type() == PostingSourceType.DEBT_RECOGNITION;
            if (ofDebt ? debtCategoryFilter != null && !debtCategoryFilter.test(e.posting().book().id())
                    : filter != null && (tx == null || !filter.test(tx))) {
                continue;
            }
            TransactionInstallment installment = tx == null || e.source().installmentId() == null ? null
                    : tx.installments().stream().filter(i -> i.id().equals(e.source().installmentId())).findFirst().orElse(null);
            UUID categoryId = e.posting().book().id();
            Debt debt = ofDebt ? debts.get(e.source().sourceId()) : null;
            lines.add(new Line(e.source().transactionId(), e.source().installmentId(),
                    installment == null ? null : installment.number(), installment == null ? null : installment.total(),
                    categoryId, e.posting().effectiveDate(), amount, ofDebt ? (debt == null ? null : debt.description())
                    : tx == null ? null : tx.description(), ofDebt ? e.source().sourceId() : null, e.posting().competence()));
            byCategory.merge(categoryId, amount, Money::add);
            total = total.add(amount);
        }
        return new EntryReport(lens, competence, List.copyOf(lines), total, Map.copyOf(byCategory));
    }
}
