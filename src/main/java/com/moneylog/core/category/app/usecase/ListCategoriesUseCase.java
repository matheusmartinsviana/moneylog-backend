package com.moneylog.core.category.app.usecase;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;

public interface ListCategoriesUseCase {

    /** Lista curta, sem paginação, ordem {@code name asc}. */
    List<Category> handle(ListCategoriesQuery query);

    record ListCategoriesQuery(UUID userId, CategoryKind kind, boolean includeArchived) {
    }
}
