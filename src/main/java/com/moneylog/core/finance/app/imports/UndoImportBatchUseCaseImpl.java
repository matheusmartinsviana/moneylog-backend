package com.moneylog.core.finance.app.imports;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.finance.app.transactions.usecase.VoidTransactionUseCase;
import com.moneylog.core.finance.app.transactions.usecase.VoidTransactionUseCase.VoidTransactionCommand;
import com.moneylog.core.importing.app.ImportBatchView;
import com.moneylog.core.importing.app.gateways.ImportBatchGateway;
import com.moneylog.core.importing.domain.ImportBatch;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportBatchNotFoundException;
import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.exception.ConflictException;
import com.moneylog.core.sk.time.TimeProvider;

/** Desfazer o lote (S-180). Sem transação própria de propósito: cada estorno é uma transação do caso de uso de estorno. */
public class UndoImportBatchUseCaseImpl implements UndoImportBatchUseCase {

    private final ImportBatchGateway batches;
    private final VoidTransactionUseCase voids;
    private final TimeProvider time;

    public UndoImportBatchUseCaseImpl(ImportBatchGateway batches, VoidTransactionUseCase voids, TimeProvider time) {
        this.batches = batches;
        this.voids = voids;
        this.time = time;
    }

    @Override
    public UndoResult handle(UndoImportBatchCommand command) {
        ImportBatch batch = batches.find(command.userId(), command.batchId())
                .orElseThrow(() -> new ImportBatchNotFoundException(command.batchId()));
        batch.ensureUndoable();
        String reason = command.reason() == null || command.reason().isBlank() ? VOID_REASON
                : VOID_REASON + ": " + command.reason().strip();
        if (reason.length() > 500) {
            reason = reason.substring(0, 500);
        }
        List<Blocked> blocked = new ArrayList<>();
        for (UUID transactionId : batches.activeTransactionIds(command.userId(), command.batchId())) {
            try {
                voids.handle(new VoidTransactionCommand(command.userId(), transactionId, reason));
            } catch (BusinessRuleException | ConflictException e) {
                blocked.add(new Blocked(transactionId, e.code(), e.getMessage()));
            }
        }
        batch.markUndone(time.now(), command.reason() == null || command.reason().isBlank() ? null : command.reason().strip(), blocked.isEmpty());
        batches.update(batch);
        return new UndoResult(new ImportBatchView(batch, batches.counts(command.userId(), batch.id())), blocked);
    }
}
