package com.moneylog.core.finance.app.accounts.usecase;

import com.moneylog.core.finance.app.accounts.AccountDetails;
import com.moneylog.core.finance.app.accounts.AccountViews;
import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.exception.AccountExceptions.AccountNotFoundException;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;

public class UnarchiveAccountUseCaseImpl implements UnarchiveAccountUseCase {

    private final AccountGateway accounts;
    private final FinancialRecordsGateway records;
    private final TimeProvider time;

    public UnarchiveAccountUseCaseImpl(AccountGateway accounts, FinancialRecordsGateway records, TimeProvider time) {
        this.accounts = accounts;
        this.records = records;
        this.time = time;
    }

    @Override
    public AccountDetails handle(UnarchiveAccountCommand command) {
        Persisted<Account> current = accounts.findById(command.userId(), command.accountId())
                .orElseThrow(() -> new AccountNotFoundException(command.accountId()));
        current.value().unarchive();
        Persisted<Account> saved = accounts.save(current.value());
        return AccountViews.of(saved, records.load(command.userId()), time.today());
    }
}
