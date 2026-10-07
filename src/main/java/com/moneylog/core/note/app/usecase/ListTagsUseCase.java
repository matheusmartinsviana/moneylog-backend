package com.moneylog.core.note.app.usecase;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.note.app.TagCount;

public interface ListTagsUseCase {

    /** As tags das notas ativas, com a contagem, da mais usada para a menos usada. */
    List<TagCount> handle(ListTagsQuery query);

    record ListTagsQuery(UUID userId) {
    }
}
