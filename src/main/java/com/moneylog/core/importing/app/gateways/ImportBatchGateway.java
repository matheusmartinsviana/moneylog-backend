package com.moneylog.core.importing.app.gateways;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.importing.domain.ImportBatch;
import com.moneylog.core.importing.domain.ImportBatchLine;
import com.moneylog.core.importing.domain.ImportBatchStatus;
import com.moneylog.core.importing.domain.ImportLineFlag;
import com.moneylog.core.importing.domain.ImportLineStatus;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;

/** Persistência de lotes e linhas de importação. Todo método filtra pelo {@code userId}; id de outro usuário não existe. */
public interface ImportBatchGateway {

    /**
     * Contadores do lote (a tela de revisão mostra "38 aceitas, 2 rejeitadas, 3 precisam de decisão").
     *
     * @param requireDecision linhas {@code PENDING} com marca que exige decisão ou {@code POSSIBLE_DUPLICATE}
     */
    record Counts(int total, int pending, int accepted, int rejected, int duplicate, int possibleDuplicate, int requireDecision) {
    }

    void insert(ImportBatch batch, List<ImportBatchLine> lines);

    Optional<ImportBatch> find(UUID userId, UUID batchId);

    /** Trava a linha do lote até o fim da transação: dois {@code apply} simultâneos se serializam aqui. */
    Optional<ImportBatch> findForUpdate(UUID userId, UUID batchId);

    /** Grava status, instantes e motivo do desfazer. */
    void update(ImportBatch batch);

    Slice<ImportBatch> list(UUID userId, ImportBatchStatus status, PageQuery page);

    /** Apaga o lote e as linhas dele (cascata). Só se usa em lote ainda não aplicado: nenhuma movimentação aponta para ele. */
    void delete(UUID userId, UUID batchId);

    Counts counts(UUID userId, UUID batchId);

    /** Lote anterior da mesma conta/cartão com o mesmo hash de arquivo (aviso, S-171). */
    Optional<UUID> findSameFile(UUID userId, UUID sourceId, String fileSha256);

    Optional<ImportBatchLine> findLine(UUID userId, UUID batchId, UUID lineId);

    void updateLine(UUID userId, ImportBatchLine line);

    Slice<ImportBatchLine> listLines(UUID userId, UUID batchId, ImportLineStatus status, ImportLineFlag flag, PageQuery page);

    /** Todas as linhas do lote, na ordem do arquivo. */
    List<ImportBatchLine> allLines(UUID userId, UUID batchId);

    /** Movimentações <b>ativas</b> criadas pelo lote (as estornadas não voltam a ser estornadas). */
    List<UUID> activeTransactionIds(UUID userId, UUID batchId);
}
