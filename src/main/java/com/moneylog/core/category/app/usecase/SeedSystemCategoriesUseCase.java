package com.moneylog.core.category.app.usecase;

import java.util.UUID;

public interface SeedSystemCategoriesUseCase {

    /**
     * Cria as categorias de sistema (S-59) que o usuário ainda não tem. Idempotente: a chave estável
     * ({@code system_key}) impede duplicar, mesmo que a categoria tenha sido renomeada ou arquivada.
     *
     * @return quantas categorias foram criadas agora
     */
    int handle(SeedSystemCategoriesCommand command);

    record SeedSystemCategoriesCommand(UUID userId) {
    }
}
