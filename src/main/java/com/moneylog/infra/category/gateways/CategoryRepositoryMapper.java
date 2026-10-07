package com.moneylog.infra.category.gateways;

import com.moneylog.core.category.domain.Category;
import com.moneylog.infra.category.persistence.CategoryEntity;

/** {@code Category} ⇄ {@code CategoryEntity}. */
public final class CategoryRepositoryMapper {

    private CategoryRepositoryMapper() {
    }

    public static Category toDomain(CategoryEntity e) {
        return Category.restore(e.getId(), e.getUserId(), e.getName(), e.getKind(), e.isSystemCategory(), e.isArchived(),
                e.getVersion());
    }
}
