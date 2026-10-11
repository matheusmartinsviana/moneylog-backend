package com.moneylog.core.note.app.usecase;

import java.util.UUID;

import com.moneylog.core.note.app.Backlink;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;

public interface ListBacklinksUseCase {

    Slice<Backlink> handle(ListBacklinksQuery query);

    record ListBacklinksQuery(UUID userId, UUID noteId, PageQuery page) {
    }
}
