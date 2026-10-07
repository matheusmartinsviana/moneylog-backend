package com.moneylog.core.category.app.usecase;

import java.util.UUID;

import com.moneylog.core.category.domain.Category;

public interface UnarchiveCategoryUseCase {

    /**
     * Desarquiva.
     *
     * @throws com.moneylog.core.category.exception.CategoryExceptions.CategoryNameTakenException já existe uma ativa
     *         com o mesmo nome no mesmo {@code kind} (a categoria continua arquivada)
     */
    Category handle(UnarchiveCategoryCommand command);

    record UnarchiveCategoryCommand(UUID userId, UUID categoryId) {
    }
}
