package com.moneylog.infra.category;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;

import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.category.app.usecase.SeedSystemCategoriesUseCase;
import com.moneylog.core.category.app.usecase.SeedSystemCategoriesUseCase.SeedSystemCategoriesCommand;

/**
 * Seed das categorias de sistema <b>em todo boot</b> (B2). O seed é idempotente por {@code system_key} (S-59), então
 * rodar sempre só completa o que falta: se o primeiro boot caiu depois de criar o dono e antes do seed, o próximo
 * boot corrige, sem depender de {@code OwnerCreated}. Roda depois do {@code OwnerBootstrap}.
 */
public class CategorySeedRunner implements ApplicationRunner, Ordered {

    private static final Logger log = LoggerFactory.getLogger(CategorySeedRunner.class);

    private final UserGateway users;
    private final SeedSystemCategoriesUseCase seed;

    public CategorySeedRunner(UserGateway users, SeedSystemCategoriesUseCase seed) {
        this.users = users;
        this.seed = seed;
    }

    @Override
    public void run(ApplicationArguments args) {
        users.findOwner().ifPresent(owner -> {
            int created = seed.handle(new SeedSystemCategoriesCommand(owner.id()));
            if (created > 0) {
                log.info("Categorias de sistema criadas no boot: {}.", created);
            }
        });
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
