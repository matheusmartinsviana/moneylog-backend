package com.moneylog.core.finance.app.transactions.usecase;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.exception.AccountExceptions.AccountNotFoundException;
import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.card.exception.CardExceptions.CardNotFoundException;
import com.moneylog.core.debt.app.gateways.DebtGateway;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.exception.DebtExceptions.DebtNotFoundException;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.finance.app.gateways.LedgerWriteGateway;
import com.moneylog.core.finance.app.transactions.TransactionDetails;
import com.moneylog.core.finance.app.transactions.TransactionDetailsAssembler;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.domain.CardInvoices;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.core.transaction.domain.TransactionVoiding;
import com.moneylog.core.transaction.domain.VoidContext;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotFoundException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionTypeNotAvailableException;

public class VoidTransactionUseCaseImpl implements VoidTransactionUseCase {

    private final TransactionGateway transactions;
    private final AccountGateway accounts;
    private final CardGateway cards;
    private final LedgerWriteGateway ledger;
    private final TransactionDetailsAssembler assembler;
    private final TimeProvider time;
    private final DebtGateway debts;

    public VoidTransactionUseCaseImpl(TransactionGateway transactions, AccountGateway accounts, LedgerWriteGateway ledger,
                                      TransactionDetailsAssembler assembler, TimeProvider time) {
        this(transactions, accounts, null, ledger, assembler, time);
    }

    public VoidTransactionUseCaseImpl(TransactionGateway transactions, AccountGateway accounts, CardGateway cards,
                                      LedgerWriteGateway ledger, TransactionDetailsAssembler assembler, TimeProvider time) {
        this(transactions, accounts, cards, ledger, assembler, time, null);
    }

    @SuppressWarnings("java:S107")
    public VoidTransactionUseCaseImpl(TransactionGateway transactions, AccountGateway accounts, CardGateway cards,
                                      LedgerWriteGateway ledger, TransactionDetailsAssembler assembler, TimeProvider time,
                                      DebtGateway debts) {
        this.debts = debts;
        this.transactions = transactions;
        this.accounts = accounts;
        this.cards = cards;
        this.ledger = ledger;
        this.assembler = assembler;
        this.time = time;
    }

    @Override
    public TransactionDetails handle(VoidTransactionCommand command) {
        UUID userId = command.userId();
        transactions.lockForUpdate(userId, command.transactionId());
        Transaction transaction = transactions.findById(userId, command.transactionId()).map(Persisted::value)
                .orElseThrow(() -> new TransactionNotFoundException(command.transactionId()));
        if (transaction.cardId() != null) {
            return voidCardTransaction(userId, command, transaction);
        }
        List<Versioned<Debt>> linkedDebts = lockLinkedDebts(userId, transaction);
        java.util.stream.Stream.of(transaction.accountId(), transaction.counterpartAccountId()).filter(java.util.Objects::nonNull)
                .sorted().distinct().forEach(accountId -> accounts.lockForUpdate(userId, accountId));
        List<Account> touched = new ArrayList<>();
        for (UUID accountId : new UUID[] {transaction.accountId(), transaction.counterpartAccountId()}) {
            if (accountId != null) {
                touched.add(accounts.findById(userId, accountId).map(Persisted::value).orElseThrow(() -> new AccountNotFoundException(accountId)));
            }
        }
        Money activeRefunds = transaction.type() == TransactionType.EXPENSE ? transactions.sumActiveRefundsOf(userId, transaction.id())
                : Money.zero(CurrencyCode.BRL);
        Instant now = time.now();

        VoidContext context = VoidContext.of(command.reason(), now, time.today()).withAccounts(touched).withActiveRefunds(activeRefunds);
        if (transaction.debtId() != null) {
            context = context.withDebt(linkedDebts.get(0).value());
        } else if (!linkedDebts.isEmpty()) {
            context = context.withShareDebts(linkedDebts.stream().map(Versioned::value).toList());
        }
        TransactionVoiding.voidTransaction(transaction, context);

        Persisted<Transaction> saved = transactions.update(transaction);
        ledger.deactivateBySource(userId, PostingSourceType.TRANSACTION, transaction.id(), VOID_REASON, now);
        for (Versioned<Debt> linked : linkedDebts) {
            if (linked.value().closure() != null) {
                debts.update(linked.value(), linked.version());
            }
        }
        return assembler.assemble(saved);
    }

    private List<Versioned<Debt>> lockLinkedDebts(UUID userId, Transaction transaction) {
        if (debts == null) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>();
        if (transaction.debtId() != null) {
            ids.add(transaction.debtId());
        } else if (transaction.type() == TransactionType.EXPENSE) {
            ids.addAll(debts.findIdsByOriginTransaction(userId, transaction.id()));
        }
        ids.stream().sorted().forEach(id -> debts.lockForUpdate(userId, id));
        List<Versioned<Debt>> linked = new ArrayList<>();
        for (UUID id : ids) {
            linked.add(debts.findById(userId, id).orElseThrow(() -> new DebtNotFoundException(id)));
        }
        return linked;
    }

    private TransactionDetails voidCardTransaction(UUID userId, VoidTransactionCommand command, Transaction transaction) {
        if (cards == null) {
            throw new TransactionTypeNotAvailableException(transaction.type());
        }
        cards.lockForUpdate(userId, transaction.cardId());
        if (transaction.accountId() != null) {
            accounts.lockForUpdate(userId, transaction.accountId());
        }
        CreditCard card = cards.findById(userId, transaction.cardId()).map(Persisted::value)
                .orElseThrow(() -> new CardNotFoundException(transaction.cardId()));
        if (transaction.accountId() != null) {
            Account account = accounts.findById(userId, transaction.accountId()).map(Persisted::value)
                    .orElseThrow(() -> new AccountNotFoundException(transaction.accountId()));
            account.ensureCanReceiveTransactions();
        }
        InvoiceChain chain = CardInvoices.chainFor(card, transactions.findAllOfCard(userId, card.id()));
        Money activeRefunds = transaction.type() == TransactionType.CARD_PURCHASE
                ? transactions.sumActiveRefundsOf(userId, transaction.id()) : Money.zero(CurrencyCode.BRL);
        Instant now = time.now();
        TransactionVoiding.voidTransaction(transaction, VoidContext.of(command.reason(), now, time.today()).withCard(card, chain)
                .withActiveRefunds(activeRefunds));
        Persisted<Transaction> saved = transactions.update(transaction);
        ledger.deactivateBySource(userId, PostingSourceType.TRANSACTION, transaction.id(), VOID_REASON, now);
        return assembler.assemble(saved);
    }
}
