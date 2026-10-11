package com.moneylog.core.finance.app.imports;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.importing.app.ImportBatchView;

public interface UndoImportBatchUseCase {

    String VOID_REASON = "importação desfeita";

    UndoResult handle(UndoImportBatchCommand command);

    record UndoImportBatchCommand(UUID userId, UUID batchId, String reason) {
    }

    record Blocked(UUID transactionId, String code, String message) {
    }

    record UndoResult(ImportBatchView batch, List<Blocked> blocked) {
        public UndoResult {
            blocked = List.copyOf(blocked);
        }
    }
}
