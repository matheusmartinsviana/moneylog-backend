package com.moneylog.core.recurrence.app.usecase;

import java.util.UUID;

import com.moneylog.core.recurrence.app.RecurrenceView;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;

public interface ListRecurrencesUseCase {

    /** Ordem {@code description asc}; {@code active} nulo = todas. */
    Slice<RecurrenceView> handle(ListRecurrencesQuery query);

    record ListRecurrencesQuery(UUID userId, Boolean active, PageQuery page) {
    }
}
