package com.moneylog.core.note.app.usecase;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.note.app.ExplicitLinkSpec;
import com.moneylog.core.note.app.NoteView;

public interface SetNoteLinksUseCase {

    /**
     * Troca <b>todos</b> os vínculos explícitos da nota (S-160). Os do corpo ({@code WIKILINK}) não mudam. Alvo inexistente ou de outro
     * usuário → {@code NOTE_LINK_TARGET_NOT_FOUND}; chave de mês fora de {@code AAAA-MM} → {@code NOTE_LINK_INVALID}. Versão desatualizada →
     * {@code VERSION_CONFLICT}. Vínculos pendentes (vindos de importação) só saem por aqui: reenviá-los exige um alvo que exista.
     */
    NoteView handle(SetNoteLinksCommand command);

    record SetNoteLinksCommand(UUID userId, UUID noteId, List<ExplicitLinkSpec> links, long version) {
    }
}
