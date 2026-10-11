package com.moneylog.core.note.app;

import java.util.UUID;

import com.moneylog.core.note.domain.NoteLinkType;
import com.moneylog.core.sk.page.PageQuery;

public record NoteQuery(UUID userId, String q, String tag, String folder, NoteLinkType linkedType, UUID linkedId, String linkedKey,
                        NoteStatus status, NoteSort sort, PageQuery page) {

    public NoteQuery {
        status = status == null ? NoteStatus.ACTIVE : status;
        q = q == null || q.isBlank() ? null : q.strip();
        sort = sort == null ? (q == null ? NoteSort.UPDATED : NoteSort.RELEVANCE) : sort;
    }
}
