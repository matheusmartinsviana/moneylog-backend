package com.moneylog.core.importing.app.usecase;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.importing.domain.ImportBatchLine;

/** Revisão das linhas do lote (aceitar, rejeitar, forçar duplicata, decidir transferência, "aprender isto"). */
public interface ReviewImportLinesUseCases {

    /** {@code REOPEN}: desfaz a aceitação enquanto o lote está em revisão (volta ao estado de origem da linha). */
    enum Action { ACCEPT, REJECT, FORCE, REOPEN }

    interface ReviewImportLineUseCase {

        /**
         * @param asTransferCounterpartAccountId só em conta e só em linha marcada {@code POSSIBLE_TRANSFER}: cria <b>uma</b> TRANSFER ao aplicar
         * @param learn grava uma regra (descrição normalizada → categoria) para as próximas sugestões
         */
        ImportBatchLine handle(ReviewImportLineCommand command);
    }

    record ReviewImportLineCommand(UUID userId, UUID batchId, UUID lineId, Action action, UUID categoryId, String method, String description,
                                   UUID asTransferCounterpartAccountId, boolean learn) {
    }

    interface BulkReviewImportLinesUseCase {
        BulkResult handle(BulkReviewCommand command);
    }

    /** Só ACCEPT e REJECT em lote; linhas que exigem decisão (ou sem categoria) voltam em {@code skipped}. */
    record BulkReviewCommand(UUID userId, UUID batchId, List<UUID> lineIds, Action action) {
    }

    record BulkResult(int updated, List<Skipped> skipped) {
    }

    record Skipped(UUID lineId, String reason) {
    }
}
