package com.moneylog.core.category.app.usecase;

import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryNotFoundException;

public class ArchiveCategoryUseCaseImpl implements ArchiveCategoryUseCase {

    private final CategoryGateway categories;

    public ArchiveCategoryUseCaseImpl(CategoryGateway categories) {
        this.categories = categories;
    }

    @Override
    public Category handle(ArchiveCategoryCommand command) {
        Category category = categories.findById(command.userId(), command.categoryId())
                .orElseThrow(() -> new CategoryNotFoundException(command.categoryId()));
        category.archive();
        return categories.save(category);
    }
}
