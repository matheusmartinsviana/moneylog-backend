package com.moneylog.core.finance.app.reports.usecase;

import java.util.Map;
import java.util.UUID;

import com.moneylog.core.category.domain.Category;
import com.moneylog.core.finance.domain.SpendingByCategory;
import com.moneylog.core.sk.time.Competence;

public interface GetSpendingByCategoryUseCase {

    /**
     * Consumo do mês por categoria (S-286): o mesmo {@code EntryReport} da lente de consumo ({@code GET /v1/entries}), uma só carga do
     * razão. {@code month} {@code null} = a competência de hoje.
     */
    SpendingByCategoryView handle(GetSpendingByCategoryQuery query);

    record GetSpendingByCategoryQuery(UUID userId, Competence month, int limit) {
    }

    /** O cálculo com as categorias resolvidas (inclusive arquivadas: o histórico não perde o nome). */
    record SpendingByCategoryView(SpendingByCategory spending, Map<UUID, Category> categories) {
    }
}
