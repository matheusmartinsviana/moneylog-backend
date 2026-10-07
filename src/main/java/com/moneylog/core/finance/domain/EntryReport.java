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

/**
 * Visão de lançamentos por competência (D1 §2.10, S-47): uma linha por posting do livro {@code EXPENSE} (ou
 * {@code INCOME}) da competência. A compra de 1.200 em 6x aparece em outubro como uma linha de 200 (1/6); o reembolso
 * aparece negativo. O consumo de uma dívida {@code PAYABLE} sem movimentação com categoria (S-137) é uma linha <b>sem
 * movimentação</b> ({@code debtId} preenchido, {@code transactionId} nulo), para a lista e o dashboard fecharem. Os totais são do filtro inteiro, calculados aqui: {@code total(CONSUMPTION, M)} =
 * {@code month.consumption} e {@code total(INCOME, M)} = {@code month.income}.
 */
public record EntryReport(EntryLens lens, Competence competence, List<Line> lines, Money total, Map<UUID, Money> byCategory) {

    /** Linha da visão: valor já no sentido da lente (consumo positivo, reembolso negativo; receita positiva). */
    public record Line(UUID transactionId, UUID installmentId, Integer installmentNumber, Integer installmentTotal,
                       UUID categoryId, LocalDate date, Money amount, String description, UUID debtId) {

        /** Linha de movimentação (sem dívida). */
        public Line(UUID transactionId, UUID installmentId, Integer installmentNumber, Integer installmentTotal, UUID categoryId,
                    LocalDate date, Money amount, String description) {
            this(transactionId, installmentId, installmentNumber, installmentTotal, categoryId, date, amount, description, null);
        }
    }

    public static EntryReport of(FinancialRecords records, Ledger ledger, EntryLens lens, Competence competence) {
        return of(records, ledger, lens, competence, null);
    }

    /**
     * Mesma visão com um predicado sobre a movimentação de origem (S-79: os filtros {@code categoryId}, {@code accountId}
     * e {@code method} viram o predicado; o total e o {@code byCategory} saem do filtro inteiro, não da página).
     *
     * @param filter {@code null} = todas as movimentações
     */
    public static EntryReport of(FinancialRecords records, Ledger ledger, EntryLens lens, Competence competence,
                                 Predicate<Transaction> filter) {
        // com filtro por movimentação, as linhas de dívida (que não têm conta nem meio de pagamento) ficam de fora
        return of(records, ledger, lens, competence, filter, filter == null ? null : categoryId -> false);
    }

    /**
     * Como {@link #of(FinancialRecords, Ledger, EntryLens, Competence, Predicate)}, com um predicado próprio para as linhas de
     * dívida de consumo, que só têm categoria (S-137): {@code null} = todas.
     *
     * @param debtCategoryFilter decide pela categoria da linha de dívida
     */
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
                    : tx == null ? null : tx.description(), ofDebt ? e.source().sourceId() : null));
            byCategory.merge(categoryId, amount, Money::add);
            total = total.add(amount);
        }
        return new EntryReport(lens, competence, List.copyOf(lines), total, Map.copyOf(byCategory));
    }
}
