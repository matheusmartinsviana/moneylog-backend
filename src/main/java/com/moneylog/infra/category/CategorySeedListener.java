package com.moneylog.infra.category;

import org.springframework.context.event.EventListener;

import com.moneylog.core.auth.app.OwnerCreated;
import com.moneylog.core.category.app.usecase.SeedSystemCategoriesUseCase;
import com.moneylog.core.category.app.usecase.SeedSystemCategoriesUseCase.SeedSystemCategoriesCommand;

/** Seed das categorias de sistema quando o dono é criado ({@link OwnerCreated} é publicado depois do commit). */
public class CategorySeedListener {

    private final SeedSystemCategoriesUseCase seed;

    public CategorySeedListener(SeedSystemCategoriesUseCase seed) {
        this.seed = seed;
    }

    @EventListener
    public void onOwnerCreated(OwnerCreated event) {
        seed.handle(new SeedSystemCategoriesCommand(event.userId()));
    }
}
