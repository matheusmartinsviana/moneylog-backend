package com.moneylog.core.importing.domain;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.importing.exception.ImportingExceptions.InvalidImportLineTransitionException;

public final class ImportLineReview {

    private final ImportLine line;
    private final DedupKey key;
    private final List<UUID> matches;
    private ImportLineStatus status;
    private DedupKey effectiveKey;
    private boolean forced;

    private ImportLineReview(ImportLine line, DedupKey key, ImportLineStatus status, List<UUID> matches) {
        this.line = Objects.requireNonNull(line, "line");
        this.key = Objects.requireNonNull(key, "key");
        this.status = Objects.requireNonNull(status, "status");
        this.matches = List.copyOf(matches);
        this.effectiveKey = key;
    }

    public static ImportLineReview classify(ImportLine line, DedupKey key, Collection<ExistingEntry> existing) {
        DuplicateClassifier.Classification classification = DuplicateClassifier.classify(line, key, existing);
        return new ImportLineReview(line, key, classification.status(), classification.matches());
    }

    public static ImportLineReview restore(ImportLine line, DedupKey key, DedupKey effectiveKey, ImportLineStatus status,
                                           List<UUID> matches, boolean forced) {
        ImportLineReview review = new ImportLineReview(line, key, status, matches);
        review.effectiveKey = Objects.requireNonNull(effectiveKey, "effectiveKey");
        review.forced = forced;
        return review;
    }

    public void accept() {
        if (status != ImportLineStatus.PENDING && status != ImportLineStatus.POSSIBLE_DUPLICATE) {
            throw new InvalidImportLineTransitionException(status, "accept");
        }
        status = ImportLineStatus.ACCEPTED;
    }

    public void reject() {
        if (status == ImportLineStatus.REJECTED) {
            throw new InvalidImportLineTransitionException(status, "reject");
        }
        if (status == ImportLineStatus.ACCEPTED) {
            resetForce();
        }
        status = ImportLineStatus.REJECTED;
    }

    public void reopen() {
        if (status != ImportLineStatus.ACCEPTED) {
            throw new InvalidImportLineTransitionException(status, "reopen");
        }
        status = forced ? ImportLineStatus.DUPLICATE : matches.isEmpty() ? ImportLineStatus.PENDING : ImportLineStatus.POSSIBLE_DUPLICATE;
        resetForce();
    }

    private void resetForce() {
        effectiveKey = key;
        forced = false;
    }

    public void forceImport(UUID importBatchId) {
        if (status != ImportLineStatus.DUPLICATE) {
            throw new InvalidImportLineTransitionException(status, "forceImport");
        }
        effectiveKey = key.forcedIn(importBatchId);
        forced = true;
        status = ImportLineStatus.ACCEPTED;
    }

    public ImportLine line() {
        return line;
    }

    public DedupKey key() {
        return key;
    }

    public DedupKey effectiveKey() {
        return effectiveKey;
    }

    public ImportLineStatus status() {
        return status;
    }

    public boolean isForced() {
        return forced;
    }

    public List<UUID> matches() {
        return matches;
    }
}
