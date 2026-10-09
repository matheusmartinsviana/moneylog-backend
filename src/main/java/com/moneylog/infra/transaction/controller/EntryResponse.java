package com.moneylog.infra.transaction.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.category.domain.Category;
import com.moneylog.core.finance.app.transactions.EntryListing;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionType;

/** {@code EntrySlice} do contrato: lançamentos da competência + {@code totals} do filtro inteiro (S-79). */
public record EntryResponse(List<Item> items, boolean hasNext, Totals totals, List<DebtItem> debtItems) {

    /** Linha de consumo que nasce de dívida sem movimentação (S-137); contrato: {@code DebtEntry}. */
    public record DebtItem(UUID debtId, LocalDate date, String competence, String description, CategoryRef category, Money amount) {
    }

    public record CategoryRef(UUID id, String name) {
    }

    public record Installment(int number, int total) {
    }

    public record Item(UUID transactionId, TransactionType transactionType, LocalDate date, String competence, String description,
                       CategoryRef category, Installment installment, String via, PaymentMethod method, UUID cardId, UUID accountId,
                       Money amount) {
    }

    public record CategoryAmount(CategoryRef category, Money amount) {
    }

    public record Totals(Money total, List<CategoryAmount> byCategory) {
    }

    public static EntryResponse of(EntryListing listing) {
        List<Item> items = listing.items().items().stream().map(row -> {
            Transaction t = row.transaction();
            var line = row.line();
            return new Item(line.transactionId(), t == null ? null : t.type(), line.date(), line.competence().toString(),
                    line.description() == null ? "" : line.description(), ref(row.category()),
                    line.installmentNumber() == null ? null : new Installment(line.installmentNumber(), line.installmentTotal()),
                    t != null && t.cardId() != null ? "CARD" : "ACCOUNT", t == null ? null : t.paymentMethod(),
                    t == null ? null : t.cardId(), t == null ? null : t.accountId(), line.amount());
        }).toList();
        List<DebtItem> debtItems = listing.debtItems().stream().map(row -> new DebtItem(row.line().debtId(), row.line().date(),
                row.line().competence().toString(), row.line().description() == null ? "" : row.line().description(),
                ref(row.category()), row.line().amount())).toList();
        return new EntryResponse(items, listing.items().hasNext(), new Totals(listing.total(),
                listing.byCategory().stream().map(c -> new CategoryAmount(ref(c.category()), c.amount())).toList()), debtItems);
    }

    private static CategoryRef ref(Category category) {
        return new CategoryRef(category.id(), category.name());
    }
}
