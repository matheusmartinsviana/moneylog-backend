package com.moneylog.core.note.app;

import java.util.UUID;

/**
 * Uma linha de wikilink gravada: onde está, o alvo como escrito, a nota a que resolve hoje ({@code null} = pendente) e a chave de nome do alvo
 * ({@code null} só em linha anterior à V6.02, preenchida no próximo recálculo).
 */
public record StoredWikilink(UUID linkId, UUID noteId, String text, UUID targetNoteId, String nameKey) {
}
