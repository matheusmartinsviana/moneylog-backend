package com.moneylog.core.category.app.usecase;

import java.util.UUID;

import com.moneylog.core.category.domain.Category;

public interface RenameCategoryUseCase {

    /**
     * Renomeia (o {@code kind} não muda).
     *
     * @throws com.moneylog.core.category.exception.CategoryExceptions.CategoryNotFoundException inexistente ou de outro usuário
     * @throws com.moneylog.core.sk.exception.VersionConflictException                             versão desatualizada
     * @throws com.moneylog.core.category.exception.CategoryExceptions.CategoryNameTakenException  nome já usado por outra ativa
     */
    Category handle(RenameCategoryCommand command);

    record RenameCategoryCommand(UUID userId, UUID categoryId, String name, long version) {
    }
}
