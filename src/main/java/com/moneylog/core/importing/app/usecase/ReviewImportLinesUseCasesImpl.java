package com.moneylog.core.importing.app.usecase;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryNotFoundException;
import com.moneylog.core.importing.app.gateways.ExistingEntriesGateway;
import com.moneylog.core.importing.app.gateways.ImportBatchGateway;
import com.moneylog.core.importing.app.gateways.ImportHistoryGateway;
import com.moneylog.core.importing.app.usecase.ReviewImportLinesUseCases.Action;
import com.moneylog.core.importing.app.usecase.ReviewImportLinesUseCases.BulkResult;
import com.moneylog.core.importing.app.usecase.ReviewImportLinesUseCases.BulkReviewCommand;
import com.moneylog.core.importing.app.usecase.ReviewImportLinesUseCases.BulkReviewImportLinesUseCase;
import com.moneylog.core.importing.app.usecase.ReviewImportLinesUseCases.ReviewImportLineCommand;
import com.moneylog.core.importing.app.usecase.ReviewImportLinesUseCases.ReviewImportLineUseCase;
import com.moneylog.core.importing.app.usecase.ReviewImportLinesUseCases.Skipped;
import com.moneylog.core.importing.domain.ImportBatch;
import com.moneylog.core.importing.domain.ImportBatchLine;
import com.moneylog.core.importing.domain.ImportDirection;
import com.moneylog.core.importing.domain.ImportLineStatus;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportBatchNotFoundException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportLineNotFoundException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportTransferLegAlreadyRecordedException;
import com.moneylog.core.importing.exception.ImportingExceptions.InvalidImportLineException;
import com.moneylog.core.importing.exception.ImportingExceptions.InvalidImportLineTransitionException;
import com.moneylog.core.sk.id.IdGenerator;

public final class ReviewImportLinesUseCasesImpl {

    private ReviewImportLinesUseCasesImpl() {
    }

    public static final class ReviewLine implements ReviewImportLineUseCase {
        private final ImportBatchGateway batches;
        private final ImportHistoryGateway history;
        private final IdGenerator ids;
        private final ExistingEntriesGateway existing;
        private final CategoryGateway categories;

        public ReviewLine(ImportBatchGateway batches, ImportHistoryGateway history, IdGenerator ids, ExistingEntriesGateway existing,
                          CategoryGateway categories) {
            this.batches = batches;
            this.history = history;
            this.ids = ids;
            this.existing = existing;
            this.categories = categories;
        }

        @Override
        public ImportBatchLine handle(ReviewImportLineCommand command) {
            ImportBatch batch = batches.findForUpdate(command.userId(), command.batchId())
                    .orElseThrow(() -> new ImportBatchNotFoundException(command.batchId()));
            batch.ensurePendingReview();
            ImportBatchLine line = batches.findLine(command.userId(), command.batchId(), command.lineId())
                    .orElseThrow(() -> new ImportLineNotFoundException(command.lineId()));
            if (command.categoryId() != null && command.action() != Action.REJECT && command.action() != Action.REOPEN) {
                validateCategory(command, line);
            }
            switch (command.action()) {
                case ACCEPT -> {
                    if (command.asTransferCounterpartAccountId() != null) {
                        if (batch.isCard()) {
                            throw new InvalidImportLineException("transferência só existe em extrato de conta.");
                        }
                        line.acceptAsTransfer(command.asTransferCounterpartAccountId());
                        if (existing.hasActiveOppositeLeg(command.userId(), batch.accountId(), line.line().direction(), line.line().amount(),
                                line.line().date())) {
                            throw new ImportTransferLegAlreadyRecordedException();
                        }
                    } else {
                        line.accept(command.categoryId(), command.method(), command.description());
                    }
                }
                case REJECT -> line.reject();
                case FORCE -> line.force(batch.id(), command.categoryId(), command.method());
                case REOPEN -> line.reopen();
            }
            if (command.learn() && line.categoryId() != null) {
                line.learnFromReview();
                history.upsertRule(command.userId(), ids.next(), line.line().direction(), line.line().normalizedDescription(),
                        line.categoryId(), line.method());
            }
            batches.updateLine(command.userId(), line);
            return line;
        }

        private void validateCategory(ReviewImportLineCommand command, ImportBatchLine line) {
            Category category = categories.findById(command.userId(), command.categoryId())
                    .orElseThrow(() -> new CategoryNotFoundException(command.categoryId()));
            category.ensureCanReceiveTransactions();
            category.ensureKind(line.line().direction() == ImportDirection.OUT ? CategoryKind.EXPENSE : CategoryKind.INCOME);
        }
    }

    public static final class BulkReview implements BulkReviewImportLinesUseCase {
        private final ImportBatchGateway batches;

        public BulkReview(ImportBatchGateway batches) {
            this.batches = batches;
        }

        @Override
        public BulkResult handle(BulkReviewCommand command) {
            if (command.action() == Action.FORCE || command.action() == Action.REOPEN) {
                throw new InvalidImportLineException("forçar duplicata ou desfazer aceitação não se faz em lote.");
            }
            ImportBatch batch = batches.findForUpdate(command.userId(), command.batchId())
                    .orElseThrow(() -> new ImportBatchNotFoundException(command.batchId()));
            batch.ensurePendingReview();
            int updated = 0;
            List<Skipped> skipped = new ArrayList<>();
            for (UUID lineId : command.lineIds().stream().distinct().toList()) {
                var found = batches.findLine(command.userId(), command.batchId(), lineId);
                if (found.isEmpty()) {
                    skipped.add(new Skipped(lineId, "LINE_NOT_FOUND"));
                    continue;
                }
                ImportBatchLine line = found.get();
                if (line.requiresDecision()) {
                    skipped.add(new Skipped(lineId, "REQUIRES_DECISION"));
                    continue;
                }
                try {
                    if (command.action() == Action.ACCEPT) {
                        if (line.status() != ImportLineStatus.PENDING) {
                            skipped.add(new Skipped(lineId, "NOT_PENDING"));
                            continue;
                        }
                        line.accept(null, null, null);
                    } else {
                        line.reject();
                    }
                } catch (InvalidImportLineException e) {
                    skipped.add(new Skipped(lineId, "NO_CATEGORY"));
                    continue;
                } catch (InvalidImportLineTransitionException e) {
                    skipped.add(new Skipped(lineId, "INVALID_TRANSITION"));
                    continue;
                }
                batches.updateLine(command.userId(), line);
                updated++;
            }
            return new BulkResult(updated, skipped);
        }
    }
}
