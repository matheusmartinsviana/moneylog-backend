package com.moneylog.core.finance.app.imports;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.importing.app.ImportBatchView;

public interface ApplyImportBatchUseCase {

    String IDEMPOTENCY_SCOPE = "POST /v1/imports/{id}/apply";

    /**
     * Cria, numa única transação, as movimentações das linhas {@code ACCEPTED} pelos <b>mesmos</b> handlers da criação manual ({@code source:
     * IMPORT}, lote e chaves de deduplicação). Recusas: lote que não está em revisão ({@code IMPORT_BATCH_NOT_PENDING}), linhas que ainda pedem
     * decisão ({@code IMPORT_LINES_REQUIRE_DECISION}) e lote sem linha aceita. Uma linha que falha desfaz tudo ({@code IMPORT_APPLY_FAILED}). Com a
     * mesma {@code Idempotency-Key}, repetir devolve o mesmo resultado.
     */
    ApplyResult handle(ApplyImportBatchCommand command);

    record ApplyImportBatchCommand(UUID userId, UUID batchId, UUID idempotencyKey) {
    }

    record ApplyResult(ImportBatchView batch, List<UUID> createdTransactionIds) {
        public ApplyResult {
            createdTransactionIds = List.copyOf(createdTransactionIds);
        }
    }
}
