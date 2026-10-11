package com.moneylog.core.finance.app.accounts.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.accounts.AccountDetails;
import com.moneylog.core.account.domain.AccountStatus;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;

public interface ListAccountsUseCase {

    Slice<AccountDetails> handle(ListAccountsQuery query);

    record ListAccountsQuery(UUID userId, AccountStatus status, PageQuery page) {
    }
}
