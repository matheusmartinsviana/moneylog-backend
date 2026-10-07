package com.moneylog.infra.category.controller;

import java.util.UUID;

import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;

public record CategoryResponse(UUID id, String name, CategoryKind kind, boolean system, boolean archived, long version) {

    public static CategoryResponse of(Category category) {
        return new CategoryResponse(category.id(), category.name(), category.kind(), category.isSystem(),
                category.isArchived(), category.version());
    }
}
