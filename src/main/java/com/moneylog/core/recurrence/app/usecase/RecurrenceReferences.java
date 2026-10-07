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
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryNotFoundException;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.core.transaction.exception.TransactionExceptions.AccountNotCashException;

/**
 * Confere as referências de um modelo de recorrência com as mesmas regras da movimentação que ele vai gerar: conta do usuário,
 * ativa e {@code CASH}; cartão do usuário e ativo; categoria do tipo certo (despesa para {@code EXPENSE} e {@code CARD_PURCHASE},
 * receita para {@code INCOME}) e ativa. Referência de outro usuário = não encontrada.
 */
final class RecurrenceReferences {

    private final CategoryGateway categories;
    private final AccountGateway accounts;
    private final CardGateway cards;

    RecurrenceReferences(CategoryGateway categories, AccountGateway accounts, CardGateway cards) {
        this.categories = categories;
        this.accounts = accounts;
        this.cards = cards;
    }

    void category(UUID userId, UUID categoryId, TransactionType type) {
        Category category = categories.findById(userId, categoryId).orElseThrow(() -> new CategoryNotFoundException(categoryId));
        category.ensureCanReceiveTransactions();
        category.ensureKind(type == TransactionType.INCOME ? CategoryKind.INCOME : CategoryKind.EXPENSE);
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
