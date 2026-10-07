package com.moneylog.core.account.app.gateways;

import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.AccountStatus;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Persisted;

/** Persistência de contas. Todo método filtra pelo {@code userId}; id de outro usuário não existe. */
public interface AccountGateway {

    /**
     * Insere uma conta nova ou grava a mudança de uma existente, devolvendo a conta com a versão gravada.
     *
     * @throws com.moneylog.core.sk.exception.VersionConflictException se outra requisição alterou antes
     */
    Persisted<Account> save(Account account);

    /**
     * Trava a linha da conta ({@code SELECT ... FOR UPDATE}) até o fim da transação (BUG-QA-08). Arquivar (que exige saldo
     * zero), corrigir o saldo inicial e receber movimentação se serializam aqui: sem a trava, uma receita que entra
     * enquanto a conta é arquivada deixa a conta arquivada com saldo. Chame <b>antes</b> de ler a conta ou o razão e, com
     * mais de uma conta, em ordem de id (evita deadlock). Não faz nada se a conta não existe ou é de outro usuário.
     */
    void lockForUpdate(UUID userId, UUID id);

    Optional<Persisted<Account>> findById(UUID userId, UUID id);

    /** Ordem {@code name asc}; {@code status} nulo = todas. Sem {@code COUNT}. */
    Slice<Persisted<Account>> list(UUID userId, AccountStatus status, PageQuery page);
}
