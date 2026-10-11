package com.moneylog.core.importing.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;

public record ExistingEntry(UUID transactionId, UUID sourceId, LocalDate date, Money amount, ImportDirection direction,
                            DedupKey dedupKey, DedupKey originalDedupKey, boolean manual, boolean active) {

    public ExistingEntry {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(direction, "direction");
    }

    public boolean hasKey(DedupKey key) {
        return key.equals(dedupKey) || key.equals(originalDedupKey);
    }
}
