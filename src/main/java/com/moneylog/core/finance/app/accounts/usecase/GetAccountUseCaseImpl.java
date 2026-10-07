package com.moneylog.core.finance.app.accounts.usecase;

import com.moneylog.core.finance.app.accounts.AccountDetails;
import com.moneylog.core.finance.app.accounts.AccountViews;
import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.account.exception.AccountExceptions.AccountNotFoundException;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.sk.time.TimeProvider;

public class GetAccountUseCaseImpl implements GetAccountUseCase {

    private final AccountGateway accounts;
    private final FinancialRecordsGateway records;
    private final TimeProvider time;

    public GetAccountUseCaseImpl(AccountGateway accounts, FinancialRecordsGateway records, TimeProvider time) {
        this.accounts = accounts;
        this.records = records;
        this.time = time;
    }

    @Override
    public AccountDetails handle(GetAccountQuery query) {
        return accounts.findById(query.userId(), query.accountId())
                .map(account -> AccountViews.of(account, records.load(query.userId()), time.today()))
                .orElseThrow(() -> new AccountNotFoundException(query.accountId()));
    }
}
