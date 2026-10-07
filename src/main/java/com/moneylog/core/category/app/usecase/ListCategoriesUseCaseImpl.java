package com.moneylog.core.category.app.usecase;

import java.util.List;

import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;

public class ListCategoriesUseCaseImpl implements ListCategoriesUseCase {

    private final CategoryGateway categories;

    public ListCategoriesUseCaseImpl(CategoryGateway categories) {
        this.categories = categories;
    }

    @Override
    public List<Category> handle(ListCategoriesQuery query) {
        return categories.list(query.userId(), query.kind(), query.includeArchived());
    }
}
