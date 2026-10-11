package com.moneylog.core.importing.domain;

import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class DuplicateClassifier {

    public static final long POSSIBLE_DUPLICATE_WINDOW_DAYS = 1;

    private DuplicateClassifier() {
    }

    public static Classification classify(ImportLine line, DedupKey key, Collection<ExistingEntry> existing) {
        Objects.requireNonNull(line, "line");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(existing, "existing");

        List<UUID> sameKey = existing.stream()
                .filter(ExistingEntry::active)
                .filter(e -> e.hasKey(key))
                .map(ExistingEntry::transactionId)
                .toList();
        if (!sameKey.isEmpty()) {
            return new Classification(ImportLineStatus.DUPLICATE, sameKey);
        }

        List<UUID> similarManual = existing.stream()
                .filter(ExistingEntry::active)
                .filter(ExistingEntry::manual)
                .filter(e -> e.sourceId().equals(line.sourceId()))
                .filter(e -> e.direction() == line.direction())
                .filter(e -> e.amount().equals(line.amount()))
                .filter(e -> Math.abs(ChronoUnit.DAYS.between(e.date(), line.date())) <= POSSIBLE_DUPLICATE_WINDOW_DAYS)
                .map(ExistingEntry::transactionId)
                .toList();
        if (!similarManual.isEmpty()) {
            return new Classification(ImportLineStatus.POSSIBLE_DUPLICATE, similarManual);
        }

        return new Classification(ImportLineStatus.PENDING, List.of());
    }

    public record Classification(ImportLineStatus status, List<UUID> matches) {
        public Classification {
            Objects.requireNonNull(status, "status");
            matches = List.copyOf(matches);
        }
    }
}
