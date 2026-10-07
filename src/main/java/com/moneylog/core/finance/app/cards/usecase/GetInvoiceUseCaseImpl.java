package com.moneylog.core.finance.app.cards.usecase;

import com.moneylog.core.card.app.gateways.InvoiceLookupGateway;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.finance.app.cards.CardViews;
import com.moneylog.core.finance.app.cards.InvoiceDetails;
import com.moneylog.core.finance.app.cards.InvoiceViews;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.sk.time.TimeProvider;

public class GetInvoiceUseCaseImpl implements GetInvoiceUseCase {

    private final InvoiceLookupGateway lookup;
    private final FinancialRecordsGateway records;
    private final TimeProvider time;

    public GetInvoiceUseCaseImpl(InvoiceLookupGateway lookup, FinancialRecordsGateway records, TimeProvider time) {
        this.lookup = lookup;
        this.records = records;
        this.time = time;
    }

    @Override
    public InvoiceDetails handle(GetInvoiceQuery query) {
        FinancialRecords loaded = records.load(query.userId());
        InvoiceResolver.Resolved resolved = InvoiceResolver.resolve(query.userId(), query.invoiceId(), lookup, loaded, time.today());
        InvoiceChain chain = CardViews.chainOf(resolved.card(), loaded);
        return InvoiceViews.details(resolved.card().id(), chain.invoice(resolved.closingDate()), InvoiceViews.index(loaded), time.today());
    }
}
