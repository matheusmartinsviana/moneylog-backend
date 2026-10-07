package com.moneylog.core.sk.persistence;

import java.time.Instant;
import java.util.Objects;

/**
 * Agregado lido ou gravado junto com os carimbos de tempo que o domínio não guarda ({@code createdAt} e
 * {@code updatedAt} existem só na persistência e na API).
 */
public record Persisted<T>(T value, Instant createdAt, Instant updatedAt) {

    public Persisted {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }
}
