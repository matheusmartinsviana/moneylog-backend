package com.moneylog.core.sk.persistence;

import java.time.Instant;
import java.util.Objects;

public record Versioned<T>(T value, long version, Instant createdAt, Instant updatedAt) {

    public Versioned {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
