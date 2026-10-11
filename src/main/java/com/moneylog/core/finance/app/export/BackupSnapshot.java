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
                             List<RecurringTransaction> recurrences, List<Goal> goals, List<LedgerEntry> ledgerPostings) {

    public static final int SCHEMA_VERSION = 1;
}
