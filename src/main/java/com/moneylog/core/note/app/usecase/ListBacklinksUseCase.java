package com.moneylog.core.note.app.usecase;

import java.util.UUID;

import com.moneylog.core.note.app.Backlink;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;

public interface ListBacklinksUseCase {

    /** Notas ativas que apontam para a nota, com o trecho de contexto (até 160 caracteres). Nota na lixeira não aparece. */
    Slice<Backlink> handle(ListBacklinksQuery query);

    record ListBacklinksQuery(UUID userId, UUID noteId, PageQuery page) {
    }
}
