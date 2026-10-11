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

public interface ImportBatchGateway {

    record Counts(int total, int pending, int accepted, int rejected, int duplicate, int possibleDuplicate, int requireDecision) {
    }

    void insert(ImportBatch batch, List<ImportBatchLine> lines);

    Optional<ImportBatch> find(UUID userId, UUID batchId);

    Optional<ImportBatch> findForUpdate(UUID userId, UUID batchId);

    void update(ImportBatch batch);

    Slice<ImportBatch> list(UUID userId, ImportBatchStatus status, PageQuery page);

    void delete(UUID userId, UUID batchId);

    Counts counts(UUID userId, UUID batchId);

    Optional<UUID> findSameFile(UUID userId, UUID sourceId, String fileSha256);

    Optional<ImportBatchLine> findLine(UUID userId, UUID batchId, UUID lineId);

    void updateLine(UUID userId, ImportBatchLine line);

    Slice<ImportBatchLine> listLines(UUID userId, UUID batchId, ImportLineStatus status, ImportLineFlag flag, PageQuery page);

    List<ImportBatchLine> allLines(UUID userId, UUID batchId);

    List<UUID> activeTransactionIds(UUID userId, UUID batchId);
}
