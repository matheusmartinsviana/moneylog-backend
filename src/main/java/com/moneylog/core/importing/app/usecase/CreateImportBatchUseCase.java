package com.moneylog.core.importing.app.usecase;

import java.util.UUID;

import com.moneylog.core.importing.app.ImportBatchView;
import com.moneylog.core.importing.parser.ColumnMapping;

public interface CreateImportBatchUseCase {

    String IDEMPOTENCY_SCOPE = "POST /v1/imports";

    /**
     * Lê o extrato, classifica cada linha (duplicata, possível duplicata, marcas) e grava o lote em {@code PENDING_REVIEW}. <b>Nenhuma movimentação
     * é criada.</b> O conteúdo do arquivo não é guardado, só o hash e as linhas lidas.
     *
     * @param format   {@code CSV} ou {@code OFX}; {@code null} = detectado
     * @param mapping  mapeamento de colunas do CSV; {@code null} = o salvo para a fonte, ou o detectado
     * @param idempotencyKey {@code null} = sem idempotência
     */
    ImportBatchView handle(CreateImportBatchCommand command);

    record CreateImportBatchCommand(UUID userId, UUID accountId, UUID cardId, byte[] content, String format, ColumnMapping mapping,
                                    UUID idempotencyKey) {
    }
}
