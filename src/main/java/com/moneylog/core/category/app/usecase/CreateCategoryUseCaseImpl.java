package com.moneylog.core.category.app.usecase;

import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryNameTakenException;
import com.moneylog.core.sk.id.IdGenerator;

public class CreateCategoryUseCaseImpl implements CreateCategoryUseCase {

    private final CategoryGateway categories;
    private final IdGenerator ids;

    public CreateCategoryUseCaseImpl(CategoryGateway categories, IdGenerator ids) {
        this.categories = categories;
        this.ids = ids;
    }

    @Override
    public Category handle(CreateCategoryCommand command) {
        Category category = Category.create(ids.next(), command.userId(), command.name(), command.kind());
        if (categories.existsActiveWithName(command.userId(), category.kind(), category.name(), null)) {
            throw new CategoryNameTakenException(category.name());
        }
        return categories.save(category);
    }
}
