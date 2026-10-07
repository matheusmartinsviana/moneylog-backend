package com.moneylog.core.finance.app.transactions;

import java.util.List;

import com.moneylog.core.category.domain.Category;
import com.moneylog.core.finance.domain.EntryReport;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.transaction.domain.Transaction;

/** Visão por competência: a página de linhas e os totais do filtro inteiro (S-79). */
public record EntryListing(Slice<Row> items, Money total, List<CategoryTotal> byCategory, List<DebtRow> debtItems) {

    public EntryListing {
        debtItems = debtItems == null ? List.of() : List.copyOf(debtItems);
    }

    /** Sem linhas de dívida. */
    public EntryListing(Slice<Row> items, Money total, List<CategoryTotal> byCategory) {
        this(items, total, byCategory, List.of());
    }

    /** Linha de consumo que nasce de dívida sem movimentação (S-137): não tem movimentação, tem a dívida. */
    public record DebtRow(EntryReport.Line line, Category category) {
    }

    /** Linha com a movimentação de origem (tipo, método, conta) e a categoria já resolvida. */
    public record Row(EntryReport.Line line, Transaction transaction, Category category) {
    }

    public record CategoryTotal(Category category, Money amount) {
    }
}
