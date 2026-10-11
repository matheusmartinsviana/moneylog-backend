package com.moneylog.core.category.app.usecase;

import java.util.UUID;

import com.moneylog.core.category.domain.Category;

public interface UnarchiveCategoryUseCase {

    Category handle(UnarchiveCategoryCommand command);

    record UnarchiveCategoryCommand(UUID userId, UUID categoryId) {
    }
}
