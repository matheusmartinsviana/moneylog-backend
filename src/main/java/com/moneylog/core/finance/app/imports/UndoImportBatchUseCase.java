package com.moneylog.core.finance.app.imports;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.importing.app.ImportBatchView;

public interface UndoImportBatchUseCase {

    /** Motivo gravado nos estornos. */
    String VOID_REASON = "importação desfeita";

    /**
     * Estorna ({@code VOIDED}, nada é apagado) as movimentações ativas do lote pelo ponto único de estorno, <b>respeitando as guardas</b>: as que não
     * puderem (compra em fatura fechada, S-36) ficam listadas em {@code blocked} e o lote vira {@code PARTIALLY_UNDONE}. Cada estorno tem a própria
     * transação, então um bloqueio não desfaz os outros; repetir o desfazer retoma o que faltou.
     */
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
