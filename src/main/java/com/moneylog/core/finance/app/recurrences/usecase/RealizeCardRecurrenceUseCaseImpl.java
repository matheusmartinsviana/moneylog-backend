package com.moneylog.core.finance.app.recurrences.usecase;

import java.util.UUID;

import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.card.exception.CardExceptions.CardNotFoundException;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryNotFoundException;
import com.moneylog.core.finance.app.gateways.LedgerWriteGateway;
import com.moneylog.core.finance.app.transactions.TransactionDetails;
import com.moneylog.core.finance.app.transactions.TransactionDetailsAssembler;
import com.moneylog.core.recurrence.app.gateways.RecurrenceGateway;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.RecurrenceNotFoundException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.domain.CardInvoices;
import com.moneylog.core.transaction.domain.Transaction;

/**
 * O lado do cartão da recorrência (T60). Uma transação: a compra e os lançamentos dela. A ocorrência fica "realizada" por
 * existir a movimentação ativa com o par {@code (recurrenceId, occurrenceDate)} ({@link RecurrenceGateway}), e o índice único
 * parcial {@code uq_transactions_recurrence_occurrence} é a última barreira contra duas realizações concorrentes.
 */
public class RealizeCardRecurrenceUseCaseImpl implements RealizeCardRecurrenceUseCase {

    private final RecurrenceGateway recurrences;
    private final CardGateway cards;
    private final CategoryGateway categories;
    private final TransactionGateway transactions;
    private final LedgerWriteGateway ledger;
    private final TransactionDetailsAssembler assembler;
    private final IdGenerator ids;
    private final TimeProvider time;

    @SuppressWarnings("java:S107")
    public RealizeCardRecurrenceUseCaseImpl(RecurrenceGateway recurrences, CardGateway cards, CategoryGateway categories,
                                            TransactionGateway transactions, LedgerWriteGateway ledger,
                                            TransactionDetailsAssembler assembler, IdGenerator ids, TimeProvider time) {
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
        // ordem global de travas: movimentações (nenhuma) → recorrência → cartão; tudo lido só depois das travas
        recurrences.lockForUpdate(userId, command.recurrenceId());
        RecurringTransaction recurrence = recurrences.findById(userId, command.recurrenceId())
                .orElseThrow(() -> new RecurrenceNotFoundException(command.recurrenceId()));
        UUID cardId = recurrence.cardId();
        if (cardId != null) {
            cards.lockForUpdate(userId, cardId);
        }
        // o modelo de conta (cardId nulo) cai na checagem do domínio: RECURRENCE_REFERENCE_MISMATCH
        CreditCard card = cardId == null ? null : cards.findById(userId, cardId).map(Persisted::value)
                .orElseThrow(() -> new CardNotFoundException(cardId));
        Category category = categories.findById(userId, recurrence.categoryId())
                .orElseThrow(() -> new CategoryNotFoundException(recurrence.categoryId()));
        InvoiceChain chain = card == null ? null : CardInvoices.chainFor(card, transactions.findAllOfCard(userId, card.id()));
        Transaction purchase = recurrence.realizeOnCard(command.occurrenceDate(), command.transactionId() == null ? ids.next() : command.transactionId(),
                command.actualAmount(), card, chain,
                time.today(), category);
        Persisted<Transaction> saved = transactions.insert(purchase);
        ledger.append(userId, purchase.entries());
        return assembler.assemble(saved);
    }
}
