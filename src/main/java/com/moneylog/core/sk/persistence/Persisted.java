package com.moneylog.core.sk.persistence;

import java.time.Instant;
import java.util.Objects;

public record Persisted<T>(T value, Instant createdAt, Instant updatedAt) {

    public Persisted {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
