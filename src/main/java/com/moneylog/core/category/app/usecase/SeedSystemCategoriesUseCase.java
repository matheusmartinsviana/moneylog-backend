package com.moneylog.core.category.app.usecase;

import java.util.UUID;

public interface SeedSystemCategoriesUseCase {

    int handle(SeedSystemCategoriesCommand command);

    record SeedSystemCategoriesCommand(UUID userId) {
    }
}
