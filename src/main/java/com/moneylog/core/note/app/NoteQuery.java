package com.moneylog.core.note.app;

import java.util.UUID;

import com.moneylog.core.note.domain.NoteLinkType;
import com.moneylog.core.sk.page.PageQuery;

/**
 * Filtros da lista e da busca. {@code q} já vem com o texto cru (o gateway escapa os curingas); {@code folder} filtra a pasta e as subpastas;
 * {@code linkedType} + {@code linkedId} (ou {@code linkedKey} no resumo mensal) filtra pelas notas ligadas à entidade.
 */
public record NoteQuery(UUID userId, String q, String tag, String folder, NoteLinkType linkedType, UUID linkedId, String linkedKey,
                        NoteStatus status, NoteSort sort, PageQuery page) {

    public NoteQuery {
        status = status == null ? NoteStatus.ACTIVE : status;
        q = q == null || q.isBlank() ? null : q.strip();
        sort = sort == null ? (q == null ? NoteSort.UPDATED : NoteSort.RELEVANCE) : sort;
    }
}
