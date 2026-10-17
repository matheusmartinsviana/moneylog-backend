package com.moneylog.core.finance.app.transactions.handlers;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
import com.moneylog.core.finance.app.transactions.ShareSettlement;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.transaction.domain.NewDebt;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Refund;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.transaction.domain.RefundOrigin;
import com.moneylog.core.transaction.domain.SharePayables;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotEditableException;
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
    private final ShareSettlement shareSettlement;
    private final TimeProvider time;

    public HandlerSupport(TransactionGateway transactions, AccountGateway accounts, CategoryGateway categories) {
        this(transactions, accounts, categories, null, null, null);
    }

    public HandlerSupport(TransactionGateway transactions, AccountGateway accounts, CategoryGateway categories, PersonGateway persons,
                          DebtGateway debts, IdGenerator ids) {
        this(transactions, accounts, categories, persons, debts, ids, null, null);
    }

    @SuppressWarnings("java:S107")
    public HandlerSupport(TransactionGateway transactions, AccountGateway accounts, CategoryGateway categories, PersonGateway persons,
                          DebtGateway debts, IdGenerator ids, ShareSettlement shareSettlement, TimeProvider time) {
        this.shareSettlement = shareSettlement;
        this.time = time;
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

    ShareSettlement shareSettlement() {
        return shareSettlement;
    }

    TimeProvider time() {
        return time;
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

    Map<UUID, Money> refundedThirds(Refund refund, RefundOrigin origin, Transaction editing) {
        if (origin == null || origin.original().purchaseShares() == null) {
            return Map.of();
        }
        if (editing != null) {
            throw new TransactionNotEditableException("o estorno de uma compra rateada não é editável; estorne e lance de novo.");
        }
        Map<UUID, Money> refunded = new TreeMap<>(SharePayables.refundedByPerson(origin.original().id(),
                transactions.findActiveRefundsOf(refund.userId(), origin.original().id())));
        if (shareSettlement != null) {
            shareSettlement.migratedRefundsOn(refund.userId(), origin.original().id()).forEach((personId, amount) -> refunded.merge(personId, amount, Money::add));
        }
        return refunded;
    }

    List<LedgerEntry> refundSettlement(Transaction refund, RefundOrigin origin) {
        if (origin == null || origin.original().purchaseShares() == null || shareSettlement == null) {
            return List.of();
        }
        Transaction original = origin.original();
        Map<UUID, Money> owed = new TreeMap<>(shareSettlement.owedNet(original.userId(), original));
        SharePayables.refundedByPerson(original.id(), List.of(refund))
                .forEach((personId, amount) -> owed.computeIfPresent(personId, (id, value) -> value.subtract(amount)));
        return shareSettlement.planConversion(original.userId(), original, owed, refund.date()).entries();
    }

    List<UUID> sharedPersonsOf(UUID userId, UUID transactionId) {
        if (transactionId == null) {
            return List.of();
        }
        return transactions.findById(userId, transactionId).map(Persisted::value)
                .map(original -> original.purchaseShares() == null ? List.<UUID>of()
                        : original.purchaseShares().personIds().stream().distinct().sorted().toList())
                .orElse(List.of());
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
