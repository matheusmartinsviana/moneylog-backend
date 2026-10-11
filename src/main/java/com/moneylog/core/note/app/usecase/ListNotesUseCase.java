package com.moneylog.core.note.app.usecase;

import com.moneylog.core.note.app.NoteQuery;
import com.moneylog.core.note.app.NoteSummary;
import com.moneylog.core.sk.page.Slice;

public interface ListNotesUseCase {

    Slice<NoteSummary> handle(NoteQuery query);
}
