package com.moneylog.core.importing.app.usecase;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.importing.domain.ImportBatchLine;

public interface ReviewImportLinesUseCases {

    enum Action { ACCEPT, REJECT, FORCE, REOPEN }

    interface ReviewImportLineUseCase {

        ImportBatchLine handle(ReviewImportLineCommand command);
    }

    record ReviewImportLineCommand(UUID userId, UUID batchId, UUID lineId, Action action, UUID categoryId, String method, String description,
                                   UUID asTransferCounterpartAccountId, boolean learn) {
    }

    interface BulkReviewImportLinesUseCase {
        BulkResult handle(BulkReviewCommand command);
    }

    record BulkReviewCommand(UUID userId, UUID batchId, List<UUID> lineIds, Action action) {
    }

    record BulkResult(int updated, List<Skipped> skipped) {
    }

    record Skipped(UUID lineId, String reason) {
    }
}
