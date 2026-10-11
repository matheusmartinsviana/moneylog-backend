package com.moneylog.core.finance.app.accounts.usecase;

import java.time.LocalDate;
import java.util.List;

import com.moneylog.core.finance.app.accounts.AccountDetails;
import com.moneylog.core.finance.app.accounts.AccountViews;
import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.exception.AccountExceptions.AccountHasReservationsException;
import com.moneylog.core.account.exception.AccountExceptions.AccountNotFoundException;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;

public class ArchiveAccountUseCaseImpl implements ArchiveAccountUseCase {

    private final AccountGateway accounts;
    private final FinancialRecordsGateway records;
    private final TimeProvider time;

    public ArchiveAccountUseCaseImpl(AccountGateway accounts, FinancialRecordsGateway records, TimeProvider time) {
        this.accounts = accounts;
        this.records = records;
        this.time = time;
    }

    @Override
    public AccountDetails handle(ArchiveAccountCommand command) {
        accounts.lockForUpdate(command.userId(), command.accountId());
        Persisted<Account> current = accounts.findById(command.userId(), command.accountId())
                .orElseThrow(() -> new AccountNotFoundException(command.accountId()));
        Account account = current.value();
        FinancialRecords loaded = records.load(command.userId());
        List<Posting> postings = loaded.ledger().entries().stream().map(LedgerEntry::posting)
                .filter(posting -> account.book().equals(posting.book())).toList();
        account.archive(postings, time.today());
        List<String> goalsWithReservation = loaded.goals().stream()
                .filter(goal -> goal.reservedOn(account.id(), LocalDate.MAX).isPositive()).map(Goal::name).toList();
        if (!goalsWithReservation.isEmpty()) {
            throw new AccountHasReservationsException(goalsWithReservation);
        }
        Persisted<Account> saved = accounts.save(account);
        return AccountViews.of(saved, records.load(command.userId()), time.today());
    }
}
