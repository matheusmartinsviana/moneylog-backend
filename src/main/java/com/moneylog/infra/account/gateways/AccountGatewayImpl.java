package com.moneylog.infra.account.gateways;

import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.AccountStatus;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.infra.account.persistence.AccountEntity;
import com.moneylog.infra.account.persistence.AccountRepositoryJpa;
import com.moneylog.infra.persistence.OffsetLimit;

public class AccountGatewayImpl implements AccountGateway {

    private final AccountRepositoryJpa repository;
    private final TimeProvider time;

    public AccountGatewayImpl(AccountRepositoryJpa repository, TimeProvider time) {
        this.repository = repository;
        this.time = time;
    }

    @Override
    public Persisted<Account> save(Account account) {
        AccountEntity entity = repository.findByIdAndUserId(account.id(), account.userId()).orElse(null);
        if (entity == null) {
            entity = new AccountEntity(account.id(), account.userId(), account.name(), account.type(), account.institution(),
                    account.openingBalance().cents(), account.currency().value(), account.openingDate(), account.status(),
                    time.now());
        } else {
            if (entity.getVersion() != account.version()) {
                throw new VersionConflictException(account.version(), entity.getVersion());
            }
            entity.apply(account.name(), account.type(), account.institution(), account.openingBalance().cents(),
                    account.openingDate(), account.status(), time.now());
        }
        return AccountRepositoryMapper.toPersisted(repository.saveAndFlush(entity));
    }

    @Override
    public void lockForUpdate(UUID userId, UUID id) {
        repository.lockByIdAndUserId(id, userId);
    }

    @Override
    public Optional<Persisted<Account>> findById(UUID userId, UUID id) {
        return repository.findByIdAndUserId(id, userId).map(AccountRepositoryMapper::toPersisted);
    }

    @Override
    public Slice<Persisted<Account>> list(UUID userId, AccountStatus status, PageQuery page) {
        if (page.isBeyondAnyData()) {
            return new Slice<>(java.util.List.of(), false);
        }
        return Slice.fromOverfetch(repository.findPage(userId, status, OffsetLimit.of(page)).stream()
                .map(AccountRepositoryMapper::toPersisted).toList(), page);
    }
}
