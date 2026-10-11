package com.moneylog.core.finance.app.accounts.usecase;

import java.time.Instant;
import java.util.List;

import com.moneylog.core.finance.app.accounts.AccountDetails;
import com.moneylog.core.finance.app.accounts.AccountViews;
import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.exception.AccountExceptions.AccountNotFoundException;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.gateways.LedgerWriteGateway;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.ledger.PostingSource;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;

public class UpdateAccountUseCaseImpl implements UpdateAccountUseCase {

    public static final String OPENING_CORRECTED = "OPENING_CORRECTED";

    private final AccountGateway accounts;
    private final LedgerWriteGateway ledger;
    private final FinancialRecordsGateway records;
    private final TimeProvider time;

    public UpdateAccountUseCaseImpl(AccountGateway accounts, LedgerWriteGateway ledger, FinancialRecordsGateway records,
                                    TimeProvider time) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.records = records;
        this.time = time;
    }

    @Override
    public AccountDetails handle(UpdateAccountCommand command) {
        accounts.lockForUpdate(command.userId(), command.accountId());
        Persisted<Account> current = accounts.findById(command.userId(), command.accountId())
                .orElseThrow(() -> new AccountNotFoundException(command.accountId()));
        Account account = current.value();
        if (account.version() != command.version()) {
            throw new VersionConflictException(command.version(), account.version());
        }
        FinancialRecords loaded = records.load(command.userId());
        account.updateDetails(command.name(), command.institution());
        List<Posting> newOpening = null;
        var type = command.type() == null ? account.type() : command.type();
        var balance = command.openingBalance() == null ? account.openingBalance() : command.openingBalance();
        var date = command.openingDate() == null ? account.openingDate() : command.openingDate();
        if (!account.openingMatches(type, balance, date)) {
            newOpening = account.correctOpening(type, balance, date, AccountViews.hasActiveMovements(loaded, account.id()));
        }
        Persisted<Account> saved = accounts.save(account);
        if (newOpening != null) {
            Instant now = time.now();
            ledger.deactivateBySource(command.userId(), PostingSourceType.ACCOUNT_OPENING, account.id(), OPENING_CORRECTED, now);
            if (!newOpening.isEmpty()) {
                ledger.append(command.userId(), LedgerEntry.activeGroup(PostingSource.accountOpening(account.id()), newOpening));
            }
        }
        return AccountViews.of(saved, records.load(command.userId()), time.today());
    }
}
