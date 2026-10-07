package com.moneylog.infra.account.gateways;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.infra.account.persistence.AccountEntity;

/** {@code Account} ⇄ {@code AccountEntity}. */
public final class AccountRepositoryMapper {

    private AccountRepositoryMapper() {
    }

    public static Account toDomain(AccountEntity e) {
        return Account.restore(e.getId(), e.getUserId(), e.getName(), e.getType(), e.getInstitution(),
                Money.ofCents(e.getOpeningBalanceCents(), CurrencyCode.of(e.getCurrency())), e.getOpeningDate(), e.getStatus(),
                e.getVersion());
    }

    public static Persisted<Account> toPersisted(AccountEntity e) {
        return new Persisted<>(toDomain(e), e.getCreatedAt(), e.getUpdatedAt());
    }
}
