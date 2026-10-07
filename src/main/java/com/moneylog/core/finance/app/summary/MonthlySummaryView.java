package com.moneylog.core.finance.app.summary;

import com.moneylog.core.category.domain.Category;
import com.moneylog.core.finance.domain.MonthlySummary;

/**
 * O resumo do mês com as categorias resolvidas (a categoria de maior consumo e a do maior gasto; {@code null} quando não há).
 */
public record MonthlySummaryView(MonthlySummary summary, Category largestCategory, Category largestExpenseCategory) {
}
