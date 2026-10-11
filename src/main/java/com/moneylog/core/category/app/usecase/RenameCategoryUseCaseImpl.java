package com.moneylog.core.category.app.usecase;

import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryNameTakenException;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryNotFoundException;
import com.moneylog.core.sk.exception.VersionConflictException;

public class RenameCategoryUseCaseImpl implements RenameCategoryUseCase {

    private final CategoryGateway categories;

    public RenameCategoryUseCaseImpl(CategoryGateway categories) {
        this.categories = categories;
    }

    @Override
    public Category handle(RenameCategoryCommand command) {
        Category category = categories.findById(command.userId(), command.categoryId())
                .orElseThrow(() -> new CategoryNotFoundException(command.categoryId()));
        if (category.version() != command.version()) {
            throw new VersionConflictException(command.version(), category.version());
        }
        category.rename(command.name());
        if (!category.isArchived()
                && categories.existsActiveWithName(command.userId(), category.kind(), category.name(), category.id())) {
            throw new CategoryNameTakenException(category.name());
        }
        return categories.save(category);
    }
}
