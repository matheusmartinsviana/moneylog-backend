package com.moneylog.core.note.app.usecase;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.note.app.ExplicitLinkSpec;
import com.moneylog.core.note.app.NoteView;

public interface CreateNoteUseCase {

    /** Escopo da chave de idempotência, próprio desta rota. */
    String IDEMPOTENCY_SCOPE = "POST /v1/notes";

    /**
     * Cria a nota (versão 0): tags e wikilinks derivados do texto, vínculos explícitos validados. Caminho ocupado por nota ativa (sem
     * diferenciar maiúsculas) → {@code NOTE_PATH_TAKEN}; 5.000 notas → {@code NOTE_LIMIT_REACHED}. Com {@code Idempotency-Key}, repetir o
     * mesmo comando devolve a mesma nota (estado atual).
     */
    NoteView handle(CreateNoteCommand command);

    record CreateNoteCommand(UUID userId, String path, String body, List<ExplicitLinkSpec> links, UUID idempotencyKey) {
    }
}
