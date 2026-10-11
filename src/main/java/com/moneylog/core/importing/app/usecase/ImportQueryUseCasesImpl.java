package com.moneylog.core.importing.app.usecase;

import java.util.UUID;

import com.moneylog.core.importing.app.ImportBatchView;
import com.moneylog.core.importing.app.gateways.ImportBatchGateway;
import com.moneylog.core.importing.app.gateways.ImportHistoryGateway;
import com.moneylog.core.importing.app.gateways.ImportHistoryGateway.StoredRule;
import com.moneylog.core.importing.app.gateways.ImportSourceGateway;
import com.moneylog.core.importing.app.usecase.ImportQueryUseCases.DeleteCategorizationRuleUseCase;
import com.moneylog.core.importing.app.usecase.ImportQueryUseCases.DiscardImportBatchUseCase;
import com.moneylog.core.importing.app.usecase.ImportQueryUseCases.GetImportBatchUseCase;
import com.moneylog.core.importing.app.usecase.ImportQueryUseCases.GetImportMappingUseCase;
import com.moneylog.core.importing.app.usecase.ImportQueryUseCases.ListCategorizationRulesUseCase;
import com.moneylog.core.importing.app.usecase.ImportQueryUseCases.ListImportBatchesUseCase;
import com.moneylog.core.importing.app.usecase.ImportQueryUseCases.ListImportLinesUseCase;
import com.moneylog.core.importing.app.usecase.ImportQueryUseCases.SaveImportMappingUseCase;
import com.moneylog.core.importing.domain.ImportBatchLine;
import com.moneylog.core.importing.domain.ImportBatchStatus;
import com.moneylog.core.importing.domain.ImportLineFlag;
import com.moneylog.core.importing.domain.ImportLineStatus;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportBatchNotFoundException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportMappingNotFoundException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportRuleNotFoundException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportSourceNotFoundException;
import com.moneylog.core.importing.parser.ColumnMapping;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;

public final class ImportQueryUseCasesImpl {

    private ImportQueryUseCasesImpl() {
    }

    public static final class GetBatch implements GetImportBatchUseCase {
        private final ImportBatchGateway batches;

        public GetBatch(ImportBatchGateway batches) {
            this.batches = batches;
        }

        @Override
        public ImportBatchView handle(UUID userId, UUID batchId) {
            return new ImportBatchView(batches.find(userId, batchId).orElseThrow(() -> new ImportBatchNotFoundException(batchId)),
                    batches.counts(userId, batchId));
        }
    }

    public static final class DiscardBatch implements DiscardImportBatchUseCase {
        private final ImportBatchGateway batches;

        public DiscardBatch(ImportBatchGateway batches) {
            this.batches = batches;
        }

        @Override
        public void handle(UUID userId, UUID batchId) {
            batches.findForUpdate(userId, batchId).orElseThrow(() -> new ImportBatchNotFoundException(batchId)).ensurePendingReview();
            batches.delete(userId, batchId);
        }
    }

    public static final class ListBatches implements ListImportBatchesUseCase {
        private final ImportBatchGateway batches;

        public ListBatches(ImportBatchGateway batches) {
            this.batches = batches;
        }

        @Override
        public Slice<ImportBatchView> handle(UUID userId, ImportBatchStatus status, PageQuery page) {
            Slice<com.moneylog.core.importing.domain.ImportBatch> slice = batches.list(userId, status, page);
            return new Slice<>(slice.items().stream().map(b -> new ImportBatchView(b, batches.counts(userId, b.id()))).toList(), slice.hasNext());
        }
    }

    public static final class ListLines implements ListImportLinesUseCase {
        private final ImportBatchGateway batches;

        public ListLines(ImportBatchGateway batches) {
            this.batches = batches;
        }

        @Override
        public Slice<ImportBatchLine> handle(UUID userId, UUID batchId, ImportLineStatus status, ImportLineFlag flag, PageQuery page) {
            batches.find(userId, batchId).orElseThrow(() -> new ImportBatchNotFoundException(batchId));
            return batches.listLines(userId, batchId, status, flag, page);
        }
    }

    public static final class ListRules implements ListCategorizationRulesUseCase {
        private final ImportHistoryGateway history;

        public ListRules(ImportHistoryGateway history) {
            this.history = history;
        }

        @Override
        public Slice<StoredRule> handle(UUID userId, PageQuery page) {
            return history.listRules(userId, page);
        }
    }

    public static final class DeleteRule implements DeleteCategorizationRuleUseCase {
        private final ImportHistoryGateway history;

        public DeleteRule(ImportHistoryGateway history) {
            this.history = history;
        }

        @Override
        public void handle(UUID userId, UUID ruleId) {
            if (!history.deleteRule(userId, ruleId)) {
                throw new ImportRuleNotFoundException(ruleId);
            }
        }
    }

    public static final class GetMapping implements GetImportMappingUseCase {
        private final ImportHistoryGateway history;
        private final ImportSourceGateway sources;

        public GetMapping(ImportHistoryGateway history, ImportSourceGateway sources) {
            this.history = history;
            this.sources = sources;
        }

        @Override
        public ColumnMapping handle(UUID userId, UUID sourceId) {
            requireSource(sources, userId, sourceId);
            return history.findMapping(userId, sourceId).orElseThrow(() -> new ImportMappingNotFoundException(sourceId));
        }
    }

    public static final class SaveMapping implements SaveImportMappingUseCase {
        private final ImportHistoryGateway history;
        private final ImportSourceGateway sources;

        public SaveMapping(ImportHistoryGateway history, ImportSourceGateway sources) {
            this.history = history;
            this.sources = sources;
        }

        @Override
        public ColumnMapping handle(UUID userId, UUID sourceId, ColumnMapping mapping) {
            requireSource(sources, userId, sourceId);
            history.saveMapping(userId, sourceId, mapping);
            return mapping;
        }
    }

    private static void requireSource(ImportSourceGateway sources, UUID userId, UUID sourceId) {
        if (!sources.accountExists(userId, sourceId) && !sources.cardExists(userId, sourceId)) {
            throw new ImportSourceNotFoundException(false, sourceId);
        }
    }
}
