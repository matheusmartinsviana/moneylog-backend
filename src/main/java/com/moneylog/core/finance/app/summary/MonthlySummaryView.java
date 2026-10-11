package com.moneylog.core.finance.app.summary;

import com.moneylog.core.category.domain.Category;
import com.moneylog.core.finance.domain.MonthlySummary;

public record MonthlySummaryView(MonthlySummary summary, Category largestCategory, Category largestExpenseCategory) {
}
