package com.moneylog.core.recurrence.app.usecase;

import java.util.UUID;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.LiquidityClass;
import com.moneylog.core.account.exception.AccountExceptions.AccountNotFoundException;
import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.exception.CardExceptions.CardNotFoundException;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.app.SystemCategories;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.exception.CategoryExceptions.DefaultCategoryUnavailableException;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryNotFoundException;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.core.transaction.exception.TransactionExceptions.AccountNotCashException;

final class RecurrenceReferences {

    private final CategoryGateway categories;
    private final AccountGateway accounts;
    private final CardGateway cards;

    RecurrenceReferences(CategoryGateway categories, AccountGateway accounts, CardGateway cards) {
        this.categories = categories;
        this.accounts = accounts;
        this.cards = cards;
    }

    Category category(UUID userId, UUID categoryId, TransactionType type) {
        CategoryKind kind = type == TransactionType.INCOME ? CategoryKind.INCOME : CategoryKind.EXPENSE;
        if (categoryId == null) {
            String key = kind == CategoryKind.INCOME ? SystemCategories.DEFAULT_INCOME_KEY : SystemCategories.DEFAULT_EXPENSE_KEY;
            Category fallback = categories.findBySystemKey(userId, key).filter(c -> !c.isArchived())
                    .orElseThrow(() -> new DefaultCategoryUnavailableException(key));
            return fallback;
        }
        Category category = categories.findById(userId, categoryId).orElseThrow(() -> new CategoryNotFoundException(categoryId));
        category.ensureCanReceiveTransactions();
        category.ensureKind(kind);
        return category;
    }

    void account(UUID userId, UUID accountId, TransactionType type) {
        Account account = accounts.findById(userId, accountId).map(Persisted::value).orElseThrow(() -> new AccountNotFoundException(accountId));
        account.ensureCanReceiveTransactions();
        if (account.liquidity() != LiquidityClass.CASH) {
            throw new AccountNotCashException(type);
        }
    }

    void card(UUID userId, UUID cardId) {
        CreditCard card = cards.findById(userId, cardId).map(Persisted::value).orElseThrow(() -> new CardNotFoundException(cardId));
        card.ensureCanReceiveTransactions();
    }
}
