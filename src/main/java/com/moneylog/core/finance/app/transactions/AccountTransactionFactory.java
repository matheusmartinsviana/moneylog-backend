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

/**
 * Monta o agregado {@link Transaction} de um comando (S-58): despacha para o handler do tipo (S-127), que carrega o que
 * precisa e chama a fábrica do domínio. É o único lugar que traduz comando em movimentação; a criação e a edição (T27)
 * usam o mesmo caminho, então as duas aplicam as mesmas regras do domínio.
 */
public class AccountTransactionFactory {

    private final TransactionGateway transactions;
    private final AccountGateway accounts;
    private final CardGateway cards;
    private final TransactionHandlers handlers;
    private final CardPurchaseHandler cardPurchase;
    private final DebtGateway debts;
    private final PersonGateway persons;

    /** Só os tipos em conta (Etapa 2): compra no cartão responde {@code TRANSACTION_TYPE_NOT_AVAILABLE}. */
    public AccountTransactionFactory(TransactionGateway transactions, AccountGateway accounts, CategoryGateway categories) {
        this(transactions, accounts, categories, null, null);
    }

    /** Tipos em conta e de cartão (Etapa 3). */
    public AccountTransactionFactory(TransactionGateway transactions, AccountGateway accounts, CategoryGateway categories,
                                     CardGateway cards, TimeProvider time) {
        this(transactions, accounts, categories, cards, time, null, null, null);
    }

    /**
     * Tipos em conta, de cartão e de dívida (Etapa 4): emprestar, receber, pagar e a divisão de despesa. Com {@code persons},
     * {@code debts} ou {@code ids} nulos, os tipos de dívida respondem {@code TRANSACTION_TYPE_NOT_AVAILABLE}.
     */
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

    /**
     * @param id      id da movimentação (nova, ou a que está sendo editada)
     * @param origin  origem da movimentação
     * @param editing movimentação que este comando substitui, ou {@code null} na criação. Na edição de um reembolso o
     *                próprio valor dele não conta como "já reembolsado" (S-81 b)
     */
    public Transaction build(CreateTransactionCommand command, UUID id, TransactionOrigin origin, Transaction editing) {
        return buildWithEffects(command, id, origin, editing).transaction();
    }

    /**
     * Como {@link #build}, e devolve também as dívidas novas que nascem com a movimentação (empréstimo, captação e partes da
     * despesa dividida): quem grava as insere <b>antes</b> da movimentação.
     */
    public BuiltTransaction buildWithEffects(CreateTransactionCommand command, UUID id, TransactionOrigin origin, Transaction editing) {
        lockFor(command, editing);
        return buildUnlockedWithEffects(command, id, origin, editing);
    }

    /**
     * Edição limitada da compra no cartão (S-121): só descrição, categoria e observação mudam; o resto do corpo precisa
     * ser o da compra. As travas são as de {@link #lockFor} (a movimentação e o cartão).
     */
    public Transaction buildCardPurchaseEdit(CardPurchase command, Transaction current, Money activeRefunds) {
        if (cardPurchase == null) {
            throw new TransactionTypeNotAvailableException(TransactionType.CARD_PURCHASE);
        }
        return cardPurchase.edit(command, current, activeRefunds);
    }

    /**
     * Monta sem travar nada: para as prévias, que rodam em transação somente leitura (o Postgres recusa
     * {@code SELECT ... FOR UPDATE} nela) e não gravam. Passa exatamente pelo mesmo handler e pelas mesmas regras do
     * domínio que a criação, só não serializa contra escritas concorrentes.
     */
    public Transaction buildUnlocked(CreateTransactionCommand command, UUID id, TransactionOrigin origin, Transaction editing) {
        return buildUnlockedWithEffects(command, id, origin, editing).transaction();
    }

    /** {@link #buildUnlocked} com as dívidas novas. */
    public BuiltTransaction buildUnlockedWithEffects(CreateTransactionCommand command, UUID id, TransactionOrigin origin,
                                                     Transaction editing) {
        return build(handlers.forCommand(command), command, id, origin, editing);
    }

    private static <C extends CreateTransactionCommand> BuiltTransaction build(TransactionTypeHandler<C> handler,
                                                                               CreateTransactionCommand command, UUID id,
                                                                               TransactionOrigin origin, Transaction editing) {
        return handler.buildWithEffects(handler.commandType().cast(command), id, origin, editing);
    }

    /**
     * Toma as travas da operação numa ordem global fixa, para duas operações nunca esperarem uma pela outra em ciclo:
     * <b>primeiro as linhas de movimentação</b> (a despesa original do reembolso, B1), <b>depois o cartão</b> (a linha de
     * {@code credit_cards} serializa toda escrita na cadeia de faturas, ADR-006 §5), <b>depois as dívidas e as pessoas</b> (Etapa
     * 4), <b>depois as contas em ordem de id</b>
     * (as do comando e as da movimentação editada, BUG-QA-08). Os casos de uso chamam antes de ler qualquer coisa e
     * {@link #build} chama de novo (travar de novo a mesma linha não custa nada).
     *
     * @param editing movimentação que o comando substitui ({@code null} na criação)
     */
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
            // dívidas antes das contas (ordem global estendida): quitações e estorno da mesma dívida se serializam; ids ordenados
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
