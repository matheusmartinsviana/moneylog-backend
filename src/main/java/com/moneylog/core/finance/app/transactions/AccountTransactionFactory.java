package com.moneylog.core.finance.app.transactions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.debt.app.gateways.DebtGateway;
import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.finance.app.transactions.handlers.AccountTypeHandlers;
import com.moneylog.core.finance.app.transactions.handlers.BuiltTransaction;
import com.moneylog.core.finance.app.transactions.handlers.DebtTransactionHandlers;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.finance.app.transactions.handlers.CardPurchaseHandler;
import com.moneylog.core.finance.app.transactions.handlers.CardRefundHandler;
import com.moneylog.core.finance.app.transactions.handlers.HandlerSupport;
import com.moneylog.core.finance.app.transactions.handlers.TransactionHandlers;
import com.moneylog.core.finance.app.transactions.handlers.TransactionTypeHandler;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.CardPurchase;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionOrigin;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionTypeNotAvailableException;

public class AccountTransactionFactory {

    private final TransactionGateway transactions;
    private final AccountGateway accounts;
    private final CardGateway cards;
    private final TransactionHandlers handlers;
    private final CardPurchaseHandler cardPurchase;
    private final DebtGateway debts;
    private final PersonGateway persons;

    public AccountTransactionFactory(TransactionGateway transactions, AccountGateway accounts, CategoryGateway categories) {
        this(transactions, accounts, categories, null, null);
    }

    public AccountTransactionFactory(TransactionGateway transactions, AccountGateway accounts, CategoryGateway categories,
                                     CardGateway cards, TimeProvider time) {
        this(transactions, accounts, categories, cards, time, null, null, null);
    }

    @SuppressWarnings("java:S107")
    public AccountTransactionFactory(TransactionGateway transactions, AccountGateway accounts, CategoryGateway categories,
                                     CardGateway cards, TimeProvider time, PersonGateway persons, DebtGateway debts, IdGenerator ids) {
        this.transactions = transactions;
        this.accounts = accounts;
        this.cards = cards;
        this.debts = debts;
        this.persons = persons;
        HandlerSupport support = new HandlerSupport(transactions, accounts, categories, persons, debts, ids);
        List<TransactionTypeHandler<?>> all = new ArrayList<>(
                AccountTypeHandlers.all(support, cards == null ? null : new CardRefundHandler(support, cards, time)));
        if (support.hasDebts()) {
            all.addAll(DebtTransactionHandlers.all(support));
        }
        this.cardPurchase = cards == null ? null : new CardPurchaseHandler(support, cards, time);
        if (cardPurchase != null) {
            all.add(cardPurchase);
        }
        this.handlers = new TransactionHandlers(all);
    }

    public Transaction build(CreateTransactionCommand command, UUID id, TransactionOrigin origin, Transaction editing) {
        return buildWithEffects(command, id, origin, editing).transaction();
    }

    public BuiltTransaction buildWithEffects(CreateTransactionCommand command, UUID id, TransactionOrigin origin, Transaction editing) {
        lockFor(command, editing);
        return buildUnlockedWithEffects(command, id, origin, editing);
    }

    public Transaction buildCardPurchaseEdit(CardPurchase command, Transaction current, Money activeRefunds) {
        if (cardPurchase == null) {
            throw new TransactionTypeNotAvailableException(TransactionType.CARD_PURCHASE);
        }
        return cardPurchase.edit(command, current, activeRefunds);
    }

    public Transaction buildUnlocked(CreateTransactionCommand command, UUID id, TransactionOrigin origin, Transaction editing) {
        return buildUnlockedWithEffects(command, id, origin, editing).transaction();
    }

    public BuiltTransaction buildUnlockedWithEffects(CreateTransactionCommand command, UUID id, TransactionOrigin origin,
                                                     Transaction editing) {
        return build(handlers.forCommand(command), command, id, origin, editing);
    }

    private static <C extends CreateTransactionCommand> BuiltTransaction build(TransactionTypeHandler<C> handler,
                                                                               CreateTransactionCommand command, UUID id,
                                                                               TransactionOrigin origin, Transaction editing) {
        return handler.buildWithEffects(handler.commandType().cast(command), id, origin, editing);
    }

    public void lockFor(CreateTransactionCommand command, Transaction editing) {
        UUID userId = command.userId();
        lockFor(handlers.forCommand(command), command, editing, userId);
    }

    private <C extends CreateTransactionCommand> void lockFor(TransactionTypeHandler<C> handler, CreateTransactionCommand command,
                                                              Transaction editing, UUID userId) {
        C typed = handler.commandType().cast(command);
        handler.transactionsToLock(typed).forEach(id -> transactions.lockForUpdate(userId, id));
        UUID commandCard = handler.cardToLock(typed);
        if (cards != null) {
            Stream.of(commandCard, editing == null ? null : editing.cardId()).filter(Objects::nonNull).distinct()
                    .sorted().forEach(cardId -> cards.lockForUpdate(userId, cardId));
        }
        if (debts != null) {
            handler.debtsToLock(typed).stream().sorted().distinct().forEach(debtId -> debts.lockForUpdate(userId, debtId));
        }
        if (persons != null) {
            handler.personsToLock(typed).stream().sorted().distinct().forEach(personId -> persons.lockForUpdate(userId, personId));
        }
        Stream.concat(handler.accountsToLock(typed).stream(),
                        editing == null ? Stream.<UUID>empty() : Stream.of(editing.accountId(), editing.counterpartAccountId()))
                .filter(Objects::nonNull).sorted().distinct().forEach(accountId -> accounts.lockForUpdate(userId, accountId));
    }
}
