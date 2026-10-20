package com.moneylog.infra.category;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;

import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.domain.UserRole;
import com.moneylog.core.category.app.usecase.SeedSystemCategoriesUseCase;
import com.moneylog.core.category.app.usecase.SeedSystemCategoriesUseCase.SeedSystemCategoriesCommand;

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
        users.findByRole(UserRole.OWNER).ifPresent(owner -> {
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
