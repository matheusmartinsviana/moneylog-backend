package com.moneylog.core.goal.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.goal.exception.GoalExceptions.GoalStateInconsistentException;

public record GoalPhoto(UUID id, int width, int height, long sizeBytes, Instant createdAt) {

    public GoalPhoto {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(createdAt, "createdAt");
        if (width <= 0 || height <= 0 || sizeBytes <= 0) {
            throw new GoalStateInconsistentException("foto com dimensões ou tamanho inválidos.");
        }
    }
}
