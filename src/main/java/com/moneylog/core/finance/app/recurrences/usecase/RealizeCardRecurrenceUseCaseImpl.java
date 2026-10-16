package com.moneylog.core.finance.app.recurrences.usecase;

import java.util.UUID;

import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.card.exception.CardExceptions.CardNotFoundException;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryNotFoundException;
import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.sk.ledger.LedgerWriteGateway;
import com.moneylog.core.finance.app.transactions.TransactionDetails;
import com.moneylog.core.finance.app.transactions.TransactionDetailsAssembler;
import com.moneylog.core.recurrence.app.RecurrenceShares;
import com.moneylog.core.recurrence.app.gateways.RecurrenceGateway;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.RecurrenceNotFoundException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.app.gateways.ForeignChargeGateway;
import com.moneylog.core.transaction.domain.CardInvoices;
import com.moneylog.core.transaction.domain.ForeignCharge;
import com.moneylog.core.transaction.domain.Transaction;

public class RealizeCardRecurrenceUseCaseImpl implements RealizeCardRecurrenceUseCase {

    private final RecurrenceGateway recurrences;
    private final CardGateway cards;
    private final CategoryGateway categories;
    private final TransactionGateway transactions;
    private final LedgerWriteGateway ledger;
    private final TransactionDetailsAssembler assembler;
    private final IdGenerator ids;
    private final TimeProvider time;
    private final PersonGateway persons;
    private final ForeignChargeGateway foreignCharges;

    @SuppressWarnings("java:S107")
    public RealizeCardRecurrenceUseCaseImpl(RecurrenceGateway recurrences, CardGateway cards, CategoryGateway categories,
                                            TransactionGateway transactions, LedgerWriteGateway ledger,
                                            TransactionDetailsAssembler assembler, IdGenerator ids, TimeProvider time) {
        this(recurrences, cards, categories, transactions, ledger, assembler, ids, time, null);
    }

    @SuppressWarnings("java:S107")
    public RealizeCardRecurrenceUseCaseImpl(RecurrenceGateway recurrences, CardGateway cards, CategoryGateway categories,
                                            TransactionGateway transactions, LedgerWriteGateway ledger,
                                            TransactionDetailsAssembler assembler, IdGenerator ids, TimeProvider time,
                                            PersonGateway persons) {
        this(recurrences, cards, categories, transactions, ledger, assembler, ids, time, persons, null);
    }

    @SuppressWarnings("java:S107")
    public RealizeCardRecurrenceUseCaseImpl(RecurrenceGateway recurrences, CardGateway cards, CategoryGateway categories,
                                            TransactionGateway transactions, LedgerWriteGateway ledger,
                                            TransactionDetailsAssembler assembler, IdGenerator ids, TimeProvider time,
                                            PersonGateway persons, ForeignChargeGateway foreignCharges) {
        this.foreignCharges = foreignCharges;
        this.persons = persons;
        this.recurrences = recurrences;
        this.cards = cards;
        this.categories = categories;
        this.transactions = transactions;
        this.ledger = ledger;
        this.assembler = assembler;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public TransactionDetails handle(RealizeCardRecurrenceCommand command) {
        UUID userId = command.userId();
        recurrences.lockForUpdate(userId, command.recurrenceId());
        RecurringTransaction recurrence = recurrences.findById(userId, command.recurrenceId())
                .orElseThrow(() -> new RecurrenceNotFoundException(command.recurrenceId()));
        if (recurrence.purchaseShares() != null) {
            RecurrenceShares.requireUsable(persons, userId, recurrence.purchaseShares().lines());
        }
        UUID cardId = recurrence.cardId();
        if (cardId != null) {
            cards.lockForUpdate(userId, cardId);
        }
        CreditCard card = cardId == null ? null : cards.findById(userId, cardId).map(Persisted::value)
                .orElseThrow(() -> new CardNotFoundException(cardId));
        Category category = categories.findById(userId, recurrence.categoryId())
                .orElseThrow(() -> new CategoryNotFoundException(recurrence.categoryId()));
        InvoiceChain chain = card == null ? null : CardInvoices.chainFor(card, transactions.findAllOfCard(userId, card.id()));
        ForeignCharge charge = recurrence.chargeFor(command.foreign().originalAmount(), command.foreign().fxRate(), command.foreign().iofBps());
        Transaction purchase = recurrence.realizeOnCard(command.occurrenceDate(), command.transactionId() == null ? ids.next() : command.transactionId(),
                command.actualAmount(), card, chain,
                time.today(), category, charge);
        Persisted<Transaction> saved = transactions.insert(purchase);
        if (charge != null) {
            foreignCharges.save(userId, purchase.id(), charge);
        }
        ledger.append(userId, purchase.entries());
        return assembler.assemble(saved);
    }
}
