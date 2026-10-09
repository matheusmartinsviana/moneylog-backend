package com.moneylog.core.goal.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.goal.exception.GoalExceptions.GoalStateInconsistentException;

/**
 * Metadados de uma foto da meta (ADR-018). Os bytes nunca moram aqui: ficam atrás da porta {@code PhotoStorage}. A posição é a da lista
 * da meta (0 = capa). {@code sizeBytes} soma a versão de exibição e a miniatura (é o que conta na cota do usuário).
 */
public record GoalPhoto(UUID id, int width, int height, long sizeBytes, Instant createdAt) {

    public GoalPhoto {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(createdAt, "createdAt");
        if (width <= 0 || height <= 0 || sizeBytes <= 0) {
            throw new GoalStateInconsistentException("foto com dimensões ou tamanho inválidos.");
        }
    }
}
