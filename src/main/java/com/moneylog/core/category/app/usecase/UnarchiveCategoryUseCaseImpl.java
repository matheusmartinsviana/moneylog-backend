package com.moneylog.core.category.app.usecase;

import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryNameTakenException;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryNotFoundException;

public class UnarchiveCategoryUseCaseImpl implements UnarchiveCategoryUseCase {

    private final CategoryGateway categories;

    public UnarchiveCategoryUseCaseImpl(CategoryGateway categories) {
        this.categories = categories;
    }

    @Override
    public Category handle(UnarchiveCategoryCommand command) {
        Category category = categories.findById(command.userId(), command.categoryId())
                .orElseThrow(() -> new CategoryNotFoundException(command.categoryId()));
        category.unarchive();
        if (categories.existsActiveWithName(command.userId(), category.kind(), category.name(), category.id())) {
            throw new CategoryNameTakenException(category.name());
        }
        return categories.save(category);
    }
}
