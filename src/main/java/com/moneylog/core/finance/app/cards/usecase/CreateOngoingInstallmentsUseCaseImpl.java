package com.moneylog.core.finance.app.cards.usecase;

import java.time.LocalDate;

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
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.domain.CardInvoices;
import com.moneylog.core.transaction.domain.CardTransactions;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionHeader;
import com.moneylog.core.transaction.domain.TransactionOrigin;

/**
 * Compra parcelada em andamento (T241). Ordem de travas: o cartão (a cadeia de faturas é dele, ADR-006 §5); a cadeia só é lida
 * depois da trava. A montagem é {@link #plan}, a mesma da prévia.
 */
public class CreateOngoingInstallmentsUseCaseImpl implements CreateOngoingInstallmentsUseCase {

    private final CardGateway cards;
    private final CategoryGateway categories;
    private final TransactionGateway transactions;
    private final LedgerWriteGateway ledger;
    private final TransactionDetailsAssembler assembler;
    private final IdGenerator ids;
    private final TimeProvider time;

    @SuppressWarnings("java:S107")
    public CreateOngoingInstallmentsUseCaseImpl(CardGateway cards, CategoryGateway categories, TransactionGateway transactions,
                                                LedgerWriteGateway ledger, TransactionDetailsAssembler assembler, IdGenerator ids,
                                                TimeProvider time) {
        this.cards = cards;
        this.categories = categories;
        this.transactions = transactions;
        this.ledger = ledger;
        this.assembler = assembler;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public TransactionDetails handle(CreateOngoingInstallmentsCommand c) {
        cards.lockForUpdate(c.userId(), c.cardId());
        CreditCard card = cards.findById(c.userId(), c.cardId()).map(Persisted::value).orElseThrow(() -> new CardNotFoundException(c.cardId()));
        InvoiceChain chain = CardInvoices.chainFor(card, transactions.findAllOfCard(c.userId(), card.id()));
        Transaction purchase = plan(c, ids.next(), card, category(categories, c), chain, time.today());
        Persisted<Transaction> saved = transactions.insert(purchase);
        ledger.append(c.userId(), purchase.entries());
        return assembler.assemble(saved);
    }

    /** A compra que o comando, o cartão e a cadeia determinam: o único ponto de montagem (criação e prévia). */
    static Transaction plan(CreateOngoingInstallmentsCommand c, java.util.UUID id, CreditCard card, Category category, InvoiceChain chain,
                            LocalDate today) {
        LocalDate date = c.firstInvoiceDate() == null ? today : c.firstInvoiceDate();
        TransactionHeader header = new TransactionHeader(id, c.userId(), date, c.description(), null, TransactionOrigin.ongoing());
        return CardTransactions.ongoingPurchase(header, c.installmentAmount(), c.totalInstallments(), c.remainingInstallments(), card,
                category, chain, today);
    }

    static Category category(CategoryGateway categories, CreateOngoingInstallmentsCommand c) {
        if (c.categoryId() == null) {
            return null;
        }
        return categories.findById(c.userId(), c.categoryId()).orElseThrow(() -> new CategoryNotFoundException(c.categoryId()));
    }
}
