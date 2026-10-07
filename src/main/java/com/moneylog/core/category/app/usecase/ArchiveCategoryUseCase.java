package com.moneylog.core.category.app.usecase;

import java.util.UUID;

import com.moneylog.core.category.domain.Category;

public interface ArchiveCategoryUseCase {

    /** Arquiva (nenhuma categoria é apagada). */
    Category handle(ArchiveCategoryCommand command);

    record ArchiveCategoryCommand(UUID userId, UUID categoryId) {
    }
}
