package com.moneylog.core.importing.app.usecase;

import java.util.UUID;

import com.moneylog.core.importing.app.ImportBatchView;
import com.moneylog.core.importing.parser.ColumnMapping;

public interface CreateImportBatchUseCase {

    String IDEMPOTENCY_SCOPE = "POST /v1/imports";

    ImportBatchView handle(CreateImportBatchCommand command);

    record CreateImportBatchCommand(UUID userId, UUID accountId, UUID cardId, byte[] content, String format, ColumnMapping mapping,
                                    UUID idempotencyKey) {
    }
}
