package com.moneylog.core.finance.app.accounts.usecase;

import com.moneylog.core.finance.app.accounts.AccountDetails;
import com.moneylog.core.finance.app.accounts.AccountViews;
import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.sk.ledger.LedgerWriteGateway;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.ledger.PostingSource;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;

import java.time.LocalDate;
import java.util.List;

import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

public class CreateAccountUseCaseImpl implements CreateAccountUseCase {

    private final AccountGateway accounts;
    private final LedgerWriteGateway ledger;
    private final FinancialRecordsGateway records;
    private final IdGenerator ids;
    private final TimeProvider time;

    public CreateAccountUseCaseImpl(AccountGateway accounts, LedgerWriteGateway ledger, FinancialRecordsGateway records,
                                    IdGenerator ids, TimeProvider time) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.records = records;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public AccountDetails handle(CreateAccountCommand command) {
        Money openingBalance = command.openingBalance() == null ? Money.zero(CurrencyCode.BRL) : command.openingBalance();
        LocalDate openingDate = command.openingDate() == null ? time.today() : command.openingDate();
        Account account = Account.open(ids.next(), command.userId(), command.name(), command.type(), command.institution(),
                openingBalance, openingDate);
        Persisted<Account> saved = accounts.save(account);
        List<Posting> opening = account.openingPostings();
        if (!opening.isEmpty()) {
            ledger.append(command.userId(), LedgerEntry.activeGroup(PostingSource.accountOpening(account.id()), opening));
        }
        return AccountViews.of(saved, records.load(command.userId()), time.today());
    }
}
