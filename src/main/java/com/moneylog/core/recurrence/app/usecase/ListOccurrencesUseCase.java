package com.moneylog.core.recurrence.app.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.recurrence.app.OccurrenceListing;
import com.moneylog.core.recurrence.domain.OccurrenceStatus;
import com.moneylog.core.sk.page.PageQuery;

public interface ListOccurrencesUseCase {

    OccurrenceListing handle(ListOccurrencesQuery query);

    int DEFAULT_WINDOW_DAYS = 30;

    int MAX_OVERDUE_OCCURRENCES = 500;

    record ListOccurrencesQuery(UUID userId, LocalDate from, LocalDate to, OccurrenceStatus status, PageQuery page) {
    }
}
