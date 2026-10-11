package com.moneylog.core.finance.app.dashboard.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.finance.app.dashboard.UpcomingView;

public interface GetUpcomingUseCase {

    UpcomingView handle(GetUpcomingQuery query);

    record GetUpcomingQuery(UUID userId, LocalDate from, LocalDate to) {
    }

    int DEFAULT_WINDOW_DAYS = 30;

    int MAX_OVERDUE_OCCURRENCES = 500;
}
