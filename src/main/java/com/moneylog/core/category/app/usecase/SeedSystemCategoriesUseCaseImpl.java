package com.moneylog.core.category.app.usecase;

import java.util.Set;

import com.moneylog.core.category.app.SystemCategories;
import com.moneylog.core.category.app.SystemCategories.Definition;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.sk.id.IdGenerator;

public class SeedSystemCategoriesUseCaseImpl implements SeedSystemCategoriesUseCase {

    private final CategoryGateway categories;
    private final IdGenerator ids;

    public SeedSystemCategoriesUseCaseImpl(CategoryGateway categories, IdGenerator ids) {
        this.categories = categories;
        this.ids = ids;
    }

    @Override
    public int handle(SeedSystemCategoriesCommand command) {
        Set<String> existing = categories.systemKeysOf(command.userId());
        int created = 0;
        for (Definition definition : SystemCategories.ALL) {
            if (existing.contains(definition.key())) {
                continue;
            }
            categories.saveSystem(Category.createSystem(ids.next(), command.userId(), definition.name(), definition.kind()),
                    definition.key());
            created++;
        }
        return created;
    }
}
