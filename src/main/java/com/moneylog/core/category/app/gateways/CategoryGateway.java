package com.moneylog.core.category.app.gateways;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;

/** Persistência de categorias. Todo método filtra pelo {@code userId}. */
public interface CategoryGateway {

    /**
     * Grava (insere se a versão ainda não existe, senão atualiza) e devolve a categoria com a versão gravada.
     *
     * @throws com.moneylog.core.sk.exception.VersionConflictException se outra requisição alterou antes
     */
    Category save(Category category);

    /** Insere uma categoria de sistema com a chave estável do seed. */
    Category saveSystem(Category category, String systemKey);

    Optional<Category> findById(UUID userId, UUID id);

    /** Ordem {@code name asc}; {@code kind} nulo = os dois. */
    List<Category> list(UUID userId, CategoryKind kind, boolean includeArchived);

    /** Existe outra categoria <b>não arquivada</b> com esse nome (sem diferenciar maiúsculas) no mesmo {@code kind}? */
    boolean existsActiveWithName(UUID userId, CategoryKind kind, String name, UUID excludingId);

    /** A categoria de sistema do usuário com essa chave estável (arquivada ou não), se existir. */
    Optional<Category> findBySystemKey(UUID userId, String systemKey);

    /** Chaves das categorias de sistema que o usuário já tem. */
    Set<String> systemKeysOf(UUID userId);
}
