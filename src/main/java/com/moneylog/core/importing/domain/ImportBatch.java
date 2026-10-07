package com.moneylog.core.importing.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.importing.exception.ImportingExceptions.ImportBatchNotPendingException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportBatchNotUndoableException;
import com.moneylog.core.importing.exception.ImportingExceptions.ImportSourceRequiredException;

/**
 * Lote de importação de um extrato (D1 §6): de <b>uma</b> conta ou <b>um</b> cartão. O arquivo bruto nunca é guardado; só o hash e as linhas lidas.
 *
 * <pre>PENDING_REVIEW ─apply▶ APPLIED ─undo▶ UNDONE | PARTIALLY_UNDONE ─undo▶ UNDONE | PARTIALLY_UNDONE</pre>
 */
public final class ImportBatch {

    public static final String WARNING_SAME_FILE = "SAME_FILE_ALREADY_IMPORTED";

    private final UUID id;
    private final UUID userId;
    private final UUID accountId;
    private final UUID cardId;
    private final String format;
    private final String fileSha256;
    private final Instant createdAt;
    private final List<String> warnings;
    private final List<String> skipped;
    private ImportBatchStatus status;
    private Instant appliedAt;
    private Instant undoneAt;
    private String undoReason;

    private ImportBatch(UUID id, UUID userId, UUID accountId, UUID cardId, String format, String fileSha256, Instant createdAt,
                        List<String> warnings, List<String> skipped, ImportBatchStatus status, Instant appliedAt, Instant undoneAt,
                        String undoReason) {
        if ((accountId == null) == (cardId == null)) {
            throw new ImportSourceRequiredException();
        }
        this.id = Objects.requireNonNull(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.accountId = accountId;
        this.cardId = cardId;
        this.format = Objects.requireNonNull(format, "format");
        this.fileSha256 = Objects.requireNonNull(fileSha256, "fileSha256");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.warnings = List.copyOf(warnings);
        this.skipped = List.copyOf(skipped);
        this.status = Objects.requireNonNull(status, "status");
        this.appliedAt = appliedAt;
        this.undoneAt = undoneAt;
        this.undoReason = undoReason;
    }

    public static ImportBatch open(UUID id, UUID userId, UUID accountId, UUID cardId, String format, String fileSha256, Instant now,
                                   List<String> warnings, List<String> skipped) {
        return new ImportBatch(id, userId, accountId, cardId, format, fileSha256, now, warnings, skipped, ImportBatchStatus.PENDING_REVIEW,
                null, null, null);
    }

    @SuppressWarnings("java:S107")
    public static ImportBatch restore(UUID id, UUID userId, UUID accountId, UUID cardId, String format, String fileSha256, Instant createdAt,
                                      List<String> warnings, List<String> skipped, ImportBatchStatus status, Instant appliedAt,
                                      Instant undoneAt, String undoReason) {
        return new ImportBatch(id, userId, accountId, cardId, format, fileSha256, createdAt, warnings, skipped, status, appliedAt, undoneAt,
                undoReason);
    }

    public void ensurePendingReview() {
        if (status != ImportBatchStatus.PENDING_REVIEW) {
            throw new ImportBatchNotPendingException(id, status.name());
        }
    }

    public void markApplied(Instant now) {
        ensurePendingReview();
        status = ImportBatchStatus.APPLIED;
        appliedAt = now;
    }

    public void ensureUndoable() {
        if (status != ImportBatchStatus.APPLIED && status != ImportBatchStatus.PARTIALLY_UNDONE) {
            throw new ImportBatchNotUndoableException(id, status.name());
        }
    }

    public void markUndone(Instant now, String reason, boolean allReverted) {
        ensureUndoable();
        status = allReverted ? ImportBatchStatus.UNDONE : ImportBatchStatus.PARTIALLY_UNDONE;
        undoneAt = now;
        undoReason = reason;
    }

    public boolean isCard() {
        return cardId != null;
    }

    /** Conta ou cartão do extrato. */
    public UUID sourceId() {
        return isCard() ? cardId : accountId;
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public UUID accountId() {
        return accountId;
    }

    public UUID cardId() {
        return cardId;
    }

    public String format() {
        return format;
    }

    public String fileSha256() {
        return fileSha256;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public List<String> warnings() {
        return warnings;
    }

    public List<String> skipped() {
        return skipped;
    }

    public ImportBatchStatus status() {
        return status;
    }

    public Instant appliedAt() {
        return appliedAt;
    }

    public Instant undoneAt() {
        return undoneAt;
    }

    public String undoReason() {
        return undoReason;
    }
}
