package com.moneylog.core.finance.app.accounts.usecase;

import com.moneylog.core.finance.app.accounts.AccountDetails;
import com.moneylog.core.finance.app.accounts.AccountViews;
import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import java.time.LocalDate;

import com.moneylog.core.finance.domain.CashPosition;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.GoalReserves;
import com.moneylog.core.finance.domain.Ledger;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.time.TimeProvider;

public class ListAccountsUseCaseImpl implements ListAccountsUseCase {

    private final AccountGateway accounts;
    private final FinancialRecordsGateway records;
    private final TimeProvider time;

    public ListAccountsUseCaseImpl(AccountGateway accounts, FinancialRecordsGateway records, TimeProvider time) {
        this.accounts = accounts;
        this.records = records;
        this.time = time;
    }

    @Override
    public Slice<AccountDetails> handle(ListAccountsQuery query) {
        var page = accounts.list(query.userId(), query.status(), query.page());
        if (page.items().isEmpty()) {
            return Slice.empty();
        }
        FinancialRecords loaded = records.load(query.userId());
        LocalDate today = time.today();
        Ledger ledger = loaded.ledger();
        GoalReserves reserves = GoalReserves.at(loaded, CashPosition.at(loaded, ledger, today));
        return new Slice<>(page.items().stream().map(account -> AccountViews.of(account, loaded, today, ledger, reserves)).toList(),
                page.hasNext());
    }
}
