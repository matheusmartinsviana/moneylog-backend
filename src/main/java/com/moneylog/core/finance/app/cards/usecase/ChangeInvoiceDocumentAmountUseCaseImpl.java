package com.moneylog.core.finance.app.cards.usecase;

import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.app.gateways.InvoiceDocumentGateway;
import com.moneylog.core.card.app.gateways.InvoiceLookupGateway;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.card.domain.InvoiceDocument;
import com.moneylog.core.finance.app.cards.CardViews;
import com.moneylog.core.finance.app.cards.InvoiceDetails;
import com.moneylog.core.finance.app.cards.InvoiceViews;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.TimeProvider;

public class ChangeInvoiceDocumentAmountUseCaseImpl implements ChangeInvoiceDocumentAmountUseCase {

    private final InvoiceLookupGateway lookup;
    private final CardGateway cards;
    private final FinancialRecordsGateway records;
    private final InvoiceDocumentGateway documents;
    private final TimeProvider time;

    public ChangeInvoiceDocumentAmountUseCaseImpl(InvoiceLookupGateway lookup, CardGateway cards, FinancialRecordsGateway records,
                                                  InvoiceDocumentGateway documents, TimeProvider time) {
        this.lookup = lookup;
        this.cards = cards;
        this.records = records;
        this.documents = documents;
        this.time = time;
    }

    @Override
    public InvoiceDetails handle(ChangeInvoiceDocumentAmountCommand command) {
        Money amount = command.documentAmount() == null ? null : InvoiceDocument.validated(command.documentAmount());
        InvoiceResolver.Locked locked = InvoiceResolver.resolveLocked(command.userId(), command.invoiceId(), lookup, cards, records, time.today());
        InvoiceResolver.Resolved resolved = locked.resolved();
        FinancialRecords loaded = locked.records();
        InvoiceChain chain = CardViews.chainOf(resolved.card(), loaded);
        InvoiceDetails details = InvoiceViews.details(resolved.card().id(), chain.invoice(resolved.closingDate()), InvoiceViews.index(loaded),
                time.today());
        long current = documents.find(command.userId(), details.invoice().id()).version();
        if (command.version() != null && command.version() != current) {
            throw new VersionConflictException(command.version(), current);
        }
        return details.withDocument(documents.change(command.userId(), resolved.card(), resolved.closingDate(), amount));
    }
}
