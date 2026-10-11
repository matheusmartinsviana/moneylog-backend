package com.moneylog.core.account.app.gateways;

import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.AccountStatus;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Persisted;

public interface AccountGateway {

    Persisted<Account> save(Account account);

    void lockForUpdate(UUID userId, UUID id);

    Optional<Persisted<Account>> findById(UUID userId, UUID id);

    Slice<Persisted<Account>> list(UUID userId, AccountStatus status, PageQuery page);
}
