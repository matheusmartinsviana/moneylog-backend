package com.moneylog.core.category.app.usecase;

import java.util.UUID;

import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;

public interface CreateCategoryUseCase {

    /** @throws com.moneylog.core.category.exception.CategoryExceptions.CategoryNameTakenException nome já usado por uma ativa */
    Category handle(CreateCategoryCommand command);

    record CreateCategoryCommand(UUID userId, String name, CategoryKind kind) {
    }
}
