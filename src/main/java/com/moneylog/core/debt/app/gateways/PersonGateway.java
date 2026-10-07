package com.moneylog.core.debt.app.gateways;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Versioned;

/** Persistência de pessoas. Todo método filtra pelo {@code userId}; pessoa de outro usuário não existe. */
public interface PersonGateway {

    /** Insere uma pessoa nova (versão 0). */
    Versioned<Person> insert(Person person);

    /**
     * Grava a mudança de uma pessoa existente.
     *
     * @param expectedVersion a versão que o chamador leu
     * @throws com.moneylog.core.sk.exception.VersionConflictException se a versão gravada é outra
     */
    Versioned<Person> update(Person person, long expectedVersion);

    /**
     * Trava a linha da pessoa ({@code SELECT ... FOR UPDATE}) até o fim da transação: arquivar e criar dívida para ela se
     * serializam. Não faz nada se não existe ou é de outro usuário.
     */
    void lockForUpdate(UUID userId, UUID id);

    Optional<Versioned<Person>> findById(UUID userId, UUID id);

    /** As pessoas dos ids informados (as que não existem ou são de outro usuário ficam de fora). */
    Map<UUID, Person> findByIds(UUID userId, Collection<UUID> ids);

    /**
     * Ordem {@code name asc, id}. {@code archived} nulo = todas; {@code query} filtra o nome sem diferenciar caixa nem
     * acentos (nulo = sem filtro). Sem {@code COUNT}.
     */
    Slice<Versioned<Person>> list(UUID userId, Boolean archived, String query, PageQuery page);
}
