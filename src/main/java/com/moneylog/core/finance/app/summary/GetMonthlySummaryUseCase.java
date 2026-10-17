package com.moneylog.core.finance.app.summary;

import java.util.UUID;

import com.moneylog.core.finance.domain.ConsumptionView;
import com.moneylog.core.sk.time.Competence;

public interface GetMonthlySummaryUseCase {

    MonthlySummaryView handle(GetMonthlySummaryQuery query);

    record GetMonthlySummaryQuery(UUID userId, Competence month, ConsumptionView view) {

        public GetMonthlySummaryQuery {
            view = ConsumptionView.orDefault(view);
        }

        public GetMonthlySummaryQuery(UUID userId, Competence month) {
            this(userId, month, null);
        }
    }
}
