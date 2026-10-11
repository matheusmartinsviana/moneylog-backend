package com.moneylog.core.finance.app.transactions.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.transactions.EntryListing;
import com.moneylog.core.finance.domain.EntryLens;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.app.query.EntryFilter;

public interface ListEntriesUseCase {

    EntryListing handle(ListEntriesQuery query);

    record ListEntriesQuery(UUID userId, Competence competence, EntryLens lens, EntryFilter filter, PageQuery page) {
    }
}
