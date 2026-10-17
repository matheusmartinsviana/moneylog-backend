package com.moneylog.core.category.app.gateways;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;

public interface CategoryGateway {

    Category save(Category category);

    Category saveSystem(Category category, String systemKey);

    Optional<Category> findById(UUID userId, UUID id);

    List<Category> list(UUID userId, CategoryKind kind, boolean includeArchived);

    boolean existsActiveWithName(UUID userId, CategoryKind kind, String name, UUID excludingId);

    Optional<Category> findBySystemKey(UUID userId, String systemKey);

    Set<String> systemKeysOf(UUID userId);

    Map<UUID, String> systemKeysById(UUID userId);

    void deleteAllOf(UUID userId);
}
