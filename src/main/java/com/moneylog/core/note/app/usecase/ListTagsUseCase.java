package com.moneylog.core.note.app.usecase;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.note.app.TagCount;

public interface ListTagsUseCase {

    List<TagCount> handle(ListTagsQuery query);

    record ListTagsQuery(UUID userId) {
    }
}
