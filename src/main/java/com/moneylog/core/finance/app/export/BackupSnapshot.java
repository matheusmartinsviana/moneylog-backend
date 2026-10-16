package com.moneylog.core.finance.app.export;

import java.time.Instant;
import java.util.List;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.transaction.domain.Transaction;

public record BackupSnapshot(int schemaVersion, Instant generatedAt, List<Account> accounts, List<Category> categories,
                             List<CreditCard> cards, List<Transaction> transactions, List<Debt> debts, List<Person> people,
                             List<RecurringTransaction> recurrences, List<Goal> goals, List<LedgerEntry> ledgerPostings,
                             BackupExtras extras) {

    public static final int SCHEMA_VERSION = 2;
    public static final int LEGACY_SCHEMA_VERSION = 1;

    public BackupSnapshot {
        extras = extras == null ? BackupExtras.EMPTY : extras;
    }

    public BackupSnapshot(int schemaVersion, Instant generatedAt, List<Account> accounts, List<Category> categories,
                          List<CreditCard> cards, List<Transaction> transactions, List<Debt> debts, List<Person> people,
                          List<RecurringTransaction> recurrences, List<Goal> goals, List<LedgerEntry> ledgerPostings) {
        this(schemaVersion, generatedAt, accounts, categories, cards, transactions, debts, people, recurrences, goals, ledgerPostings,
                BackupExtras.EMPTY);
    }
}
