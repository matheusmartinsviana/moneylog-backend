package com.moneylog.core.transaction.app.query;

import java.util.UUID;
import java.util.function.Predicate;

import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;

/**
 * Filtros da visão por competência (S-79): viram o predicado de {@code EntryReport.of}. {@code accountId} é a conta da
 * movimentação (as linhas de consumo e receita vêm de despesa, receita e reembolso, que têm uma conta só).
 */
public record EntryFilter(UUID categoryId, UUID accountId, PaymentMethod method) {

    /**
     * A linha de dívida de consumo (S-137) só tem categoria: com filtro de conta ou de meio de pagamento ela não entra; só com a
     * categoria, entra se for a dela.
     */
    public Predicate<UUID> debtCategoryPredicate() {
        return debtCategory -> accountId == null && method == null && (categoryId == null || categoryId.equals(debtCategory));
    }

    public Predicate<Transaction> predicate() {
        return t -> (categoryId == null || categoryId.equals(t.categoryId()))
                && (accountId == null || accountId.equals(t.accountId()))
                && (method == null || t.paymentMethod() == method);
    }
}
