package com.moneylog.core.category.app.usecase;

import java.util.UUID;

import com.moneylog.core.category.domain.Category;

public interface RenameCategoryUseCase {

    Category handle(RenameCategoryCommand command);

    record RenameCategoryCommand(UUID userId, UUID categoryId, String name, long version) {
    }
}
