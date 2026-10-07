package com.moneylog.core.note.app;

import java.util.UUID;

/** Nova resolução de uma linha de wikilink ({@code targetNoteId} nulo = pendente) e a chave de nome do alvo. */
public record WikilinkUpdate(UUID linkId, UUID targetNoteId, String nameKey) {
}
