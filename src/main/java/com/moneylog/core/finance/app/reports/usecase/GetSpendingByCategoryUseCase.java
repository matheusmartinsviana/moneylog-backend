package com.moneylog.core.finance.app.reports.usecase;

import java.util.Map;
import java.util.UUID;

import com.moneylog.core.category.domain.Category;
import com.moneylog.core.finance.domain.ConsumptionView;
import com.moneylog.core.finance.domain.SpendingByCategory;
import com.moneylog.core.sk.time.Competence;

public interface GetSpendingByCategoryUseCase {

    SpendingByCategoryView handle(GetSpendingByCategoryQuery query);

    record GetSpendingByCategoryQuery(UUID userId, Competence month, int limit, ConsumptionView view) {

        public GetSpendingByCategoryQuery {
            view = ConsumptionView.orDefault(view);
        }

        public GetSpendingByCategoryQuery(UUID userId, Competence month, int limit) {
            this(userId, month, limit, null);
        }
    }

    record SpendingByCategoryView(SpendingByCategory spending, Map<UUID, Category> categories) {
    }
}
