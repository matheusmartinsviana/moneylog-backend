package com.moneylog.core.finance.app.accounts.usecase;

import com.moneylog.core.finance.app.accounts.AccountDetails;
import com.moneylog.core.finance.app.accounts.AccountViews;
import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.gateways.LedgerWriteGateway;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.ledger.PostingSource;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;

import java.util.List;

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
        Account account = Account.open(ids.next(), command.userId(), command.name(), command.type(), command.institution(),
                command.openingBalance(), command.openingDate());
        Persisted<Account> saved = accounts.save(account);
        List<Posting> opening = account.openingPostings();
        if (!opening.isEmpty()) {
            ledger.append(command.userId(), LedgerEntry.activeGroup(PostingSource.accountOpening(account.id()), opening));
        }
        return AccountViews.of(saved, records.load(command.userId()), time.today());
    }
}
