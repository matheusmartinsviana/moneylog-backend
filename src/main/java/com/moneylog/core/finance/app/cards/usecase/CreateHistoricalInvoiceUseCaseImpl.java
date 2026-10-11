package com.moneylog.core.finance.app.cards.usecase;

import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.card.exception.CardExceptions.CardNotFoundException;
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

public class CreateHistoricalInvoiceUseCaseImpl implements CreateHistoricalInvoiceUseCase {

    private final CardGateway cards;
    private final TransactionGateway transactions;
    private final LedgerWriteGateway ledger;
    private final TransactionDetailsAssembler assembler;
    private final IdGenerator ids;
    private final TimeProvider time;

    public CreateHistoricalInvoiceUseCaseImpl(CardGateway cards, TransactionGateway transactions, LedgerWriteGateway ledger,
                                              TransactionDetailsAssembler assembler, IdGenerator ids, TimeProvider time) {
        this.cards = cards;
        this.transactions = transactions;
        this.ledger = ledger;
        this.assembler = assembler;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public TransactionDetails handle(CreateHistoricalInvoiceCommand c) {
        cards.lockForUpdate(c.userId(), c.cardId());
        CreditCard card = cards.findById(c.userId(), c.cardId()).map(Persisted::value).orElseThrow(() -> new CardNotFoundException(c.cardId()));
        InvoiceChain chain = CardInvoices.chainFor(card, transactions.findAllOfCard(c.userId(), card.id()));
        TransactionHeader header = new TransactionHeader(ids.next(), c.userId(), c.closingDate(), c.description(), null, TransactionOrigin.historical());
        Transaction item = CardTransactions.historicalInvoiceItem(header, c.amount(), card, c.closingDate(), chain, time.today());
        Persisted<Transaction> saved = transactions.insert(item);
        ledger.append(c.userId(), item.entries());
        return assembler.assemble(saved);
    }
}
