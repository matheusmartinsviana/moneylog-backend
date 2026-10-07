package com.moneylog.infra.importing.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.finance.app.imports.ApplyImportBatchUseCase.ApplyResult;
import com.moneylog.core.finance.app.imports.UndoImportBatchUseCase.Blocked;
import com.moneylog.core.finance.app.imports.UndoImportBatchUseCase.UndoResult;
import com.moneylog.core.importing.app.ImportBatchView;
import com.moneylog.core.importing.app.gateways.ImportBatchGateway.Counts;
import com.moneylog.core.importing.app.gateways.ImportHistoryGateway.StoredRule;
import com.moneylog.core.importing.app.usecase.ReviewImportLinesUseCases.BulkResult;
import com.moneylog.core.importing.domain.ImportBatch;
import com.moneylog.core.importing.domain.ImportBatchLine;
import com.moneylog.core.importing.domain.ImportBatchStatus;
import com.moneylog.core.importing.domain.ImportDirection;
import com.moneylog.core.importing.domain.ImportLineFlag;
import com.moneylog.core.importing.domain.ImportLineStatus;
import com.moneylog.core.sk.money.Money;

/** Respostas de {@code /v1/imports} (contrato: {@code ImportBatch}, {@code ImportLine}). Tudo calculado no backend. */
public final class ImportResponses {

    private ImportResponses() {
    }

    public record CountsResponse(int total, int pending, int accepted, int rejected, int duplicate, int possibleDuplicate, int requireDecision) {
        static CountsResponse of(Counts c) {
            return new CountsResponse(c.total(), c.pending(), c.accepted(), c.rejected(), c.duplicate(), c.possibleDuplicate(), c.requireDecision());
        }
    }

    public record BatchResponse(UUID id, UUID accountId, UUID cardId, String format, ImportBatchStatus status, List<String> warnings,
                                List<String> skipped, CountsResponse counts, Instant createdAt, Instant appliedAt, Instant undoneAt,
                                String undoReason) {
        public static BatchResponse of(ImportBatchView view) {
            ImportBatch b = view.batch();
            return new BatchResponse(b.id(), b.accountId(), b.cardId(), b.format(), b.status(), b.warnings(), b.skipped(),
                    CountsResponse.of(view.counts()), b.createdAt(), b.appliedAt(), b.undoneAt(), b.undoReason());
        }
    }

    public record SuggestionResponse(UUID categoryId, String method, String confidence) {
    }

    public record LineResponse(UUID id, int position, LocalDate date, Money amount, ImportDirection direction, String description,
                               String normalizedDescription, ImportLineStatus status, List<ImportLineFlag> flags, boolean requiresDecision,
                               String rejectReason, UUID categoryId, String method, String editedDescription, UUID counterpartAccountId,
                               SuggestionResponse suggestion, List<UUID> matches, UUID transactionId) {
        public static LineResponse of(ImportBatchLine l) {
            var s = l.suggestion();
            return new LineResponse(l.id(), l.position(), l.line().date(), l.line().amount(), l.line().direction(), l.line().description(),
                    l.line().normalizedDescription(), l.status(), l.flags().stream().sorted().toList(), l.requiresDecision(), l.rejectReason(),
                    l.categoryId(), l.method(), l.descriptionOverride(), l.counterpartAccountId(),
                    s == null ? null : new SuggestionResponse(s.categoryId(), s.method(), s.confidence().name()), l.review().matches(),
                    l.transactionId());
        }
    }

    public record SkippedResponse(UUID lineId, String reason) {
    }

    public record BulkResponse(int updated, List<SkippedResponse> skipped) {
        static BulkResponse of(BulkResult r) {
            return new BulkResponse(r.updated(), r.skipped().stream().map(s -> new SkippedResponse(s.lineId(), s.reason())).toList());
        }
    }

    public record AppliedResponse(BatchResponse batch, List<UUID> created) {
        static AppliedResponse of(ApplyResult r) {
            return new AppliedResponse(BatchResponse.of(r.batch()), r.createdTransactionIds());
        }
    }

    public record BlockedResponse(UUID transactionId, String code, String message) {
        static BlockedResponse of(Blocked b) {
            return new BlockedResponse(b.transactionId(), b.code(), b.message());
        }
    }

    public record UndoneResponse(BatchResponse batch, List<BlockedResponse> blocked) {
        static UndoneResponse of(UndoResult r) {
            return new UndoneResponse(BatchResponse.of(r.batch()), r.blocked().stream().map(BlockedResponse::of).toList());
        }
    }

    public record RuleResponse(UUID id, ImportDirection direction, String normalizedDescription, UUID categoryId, String method) {
        static RuleResponse of(StoredRule r) {
            return new RuleResponse(r.id(), r.direction(), r.normalizedDescription(), r.categoryId(), r.method());
        }
    }
}
