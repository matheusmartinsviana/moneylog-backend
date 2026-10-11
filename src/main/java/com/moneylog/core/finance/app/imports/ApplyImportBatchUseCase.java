package com.moneylog.core.finance.app.imports;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.importing.app.ImportBatchView;

public interface ApplyImportBatchUseCase {

    String IDEMPOTENCY_SCOPE = "POST /v1/imports/{id}/apply";

    ApplyResult handle(ApplyImportBatchCommand command);

    record ApplyImportBatchCommand(UUID userId, UUID batchId, UUID idempotencyKey) {
    }

    record ApplyResult(ImportBatchView batch, List<UUID> createdTransactionIds) {
        public ApplyResult {
            createdTransactionIds = List.copyOf(createdTransactionIds);
        }
    }
}
