package com.moneylog.core.finance.app.transactions.handlers;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.exception.AccountExceptions.AccountNotFoundException;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.app.SystemCategories;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.category.exception.CategoryExceptions.DefaultCategoryUnavailableException;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryNotFoundException;
import com.moneylog.core.debt.app.gateways.DebtGateway;
import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.debt.exception.DebtExceptions.DebtNotFoundException;
import com.moneylog.core.debt.exception.DebtExceptions.PersonNotFoundException;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Common;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.NewDebtSpec;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.transaction.domain.NewDebt;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Refund;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.RefundOrigin;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotFoundException;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionHeader;
import com.moneylog.core.transaction.domain.TransactionOrigin;

public class HandlerSupport {

    private final TransactionGateway transactions;
    private final AccountGateway accounts;
    private final CategoryGateway categories;
    private final PersonGateway persons;
    private final DebtGateway debts;
    private final IdGenerator ids;

    public HandlerSupport(TransactionGateway transactions, AccountGateway accounts, CategoryGateway categories) {
        this(transactions, accounts, categories, null, null, null);
    }

    public HandlerSupport(TransactionGateway transactions, AccountGateway accounts, CategoryGateway categories, PersonGateway persons,
                          DebtGateway debts, IdGenerator ids) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.categories = categories;
        this.persons = persons;
        this.debts = debts;
        this.ids = ids;
    }

    public boolean hasDebts() {
        return persons != null && debts != null && ids != null;
    }

    Person person(UUID userId, UUID personId) {
        return persons.findById(userId, personId).map(Versioned::value).orElseThrow(() -> new PersonNotFoundException(personId));
    }

    Debt debt(UUID userId, UUID debtId) {
        return debts.findById(userId, debtId).map(Versioned::value).orElseThrow(() -> new DebtNotFoundException(debtId));
    }

    NewDebt newDebt(UUID userId, NewDebtSpec spec, TransactionHeader header) {
        return new NewDebt(ids.next(), person(userId, spec.personId()), spec.expectedDate(),
                spec.description() == null || spec.description().isBlank() ? header.description() : spec.description());
    }

    List<Debt> debtsOriginatedBy(UUID userId, UUID transactionId) {
        return debts == null ? List.of() : debts.findByOriginTransaction(userId, transactionId).stream().map(Versioned::value).toList();
    }

    IdGenerator ids() {
        return ids;
    }

    TransactionGateway transactions() {
        return transactions;
    }

    TransactionHeader header(Common common, UUID id, TransactionOrigin origin) {
        return new TransactionHeader(id, common.userId(), common.date(), common.description(), common.notes(), origin);
    }

    Account account(UUID userId, UUID accountId) {
        return accounts.findById(userId, accountId).map(Persisted::value).orElseThrow(() -> new AccountNotFoundException(accountId));
    }

    Category category(UUID userId, UUID categoryId, Transaction editing, CategoryKind kind) {
        if (categoryId == null) {
            return defaultCategory(userId, kind);
        }
        Category category = categories.findById(userId, categoryId).orElseThrow(() -> new CategoryNotFoundException(categoryId));
        return editing != null && categoryId.equals(editing.categoryId()) ? category.forExistingUse() : category;
    }

    Category defaultCategory(UUID userId, CategoryKind kind) {
        String key = kind == CategoryKind.INCOME ? SystemCategories.DEFAULT_INCOME_KEY : SystemCategories.DEFAULT_EXPENSE_KEY;
        return categories.findBySystemKey(userId, key).filter(c -> !c.isArchived())
                .orElseThrow(() -> new DefaultCategoryUnavailableException(key));
    }

    Category refundCategory(Refund c, RefundOrigin origin, Transaction editing) {
        if (c.categoryId() == null && origin != null && origin.original().categoryId() != null) {
            return category(c.userId(), origin.original().categoryId(), editing, CategoryKind.EXPENSE);
        }
        return category(c.userId(), c.categoryId(), editing, CategoryKind.EXPENSE);
    }

    RefundOrigin refundOrigin(Refund refund, Transaction editing) {
        if (refund.refundOfTransactionId() == null) {
            return null;
        }
        transactions.lockForUpdate(refund.userId(), refund.refundOfTransactionId());
        Transaction original = transactions.findById(refund.userId(), refund.refundOfTransactionId()).map(Persisted::value)
                .orElseThrow(() -> new TransactionNotFoundException(refund.refundOfTransactionId()));
        Money alreadyRefunded = transactions.sumActiveRefundsOf(refund.userId(), original.id());
        if (editing != null && editing.isActive() && original.id().equals(editing.refundOfId())) {
            alreadyRefunded = alreadyRefunded.subtract(editing.amount());
        }
        for (Debt shared : debtsOriginatedBy(refund.userId(), original.id())) {
            alreadyRefunded = alreadyRefunded.add(shared.principal());
        }
        return new RefundOrigin(original, alreadyRefunded);
    }
}
