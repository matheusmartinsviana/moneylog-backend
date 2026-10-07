package com.moneylog.core.note.app.usecase;

import java.util.List;

import com.moneylog.core.note.app.TagCount;
import com.moneylog.core.note.app.gateways.NoteGateway;

public class ListTagsUseCaseImpl implements ListTagsUseCase {

    private final NoteGateway notes;

    public ListTagsUseCaseImpl(NoteGateway notes) {
        this.notes = notes;
    }

    @Override
    public List<TagCount> handle(ListTagsQuery query) {
        return notes.tagCounts(query.userId());
    }
}
