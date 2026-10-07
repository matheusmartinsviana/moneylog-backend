package com.moneylog.core.finance.app.cards.usecase;

import java.util.ArrayList;
import java.util.List;

import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.CreditCardInvoice;
import com.moneylog.core.card.domain.InvoiceIds;
import com.moneylog.core.card.domain.InvoiceStatus;
import com.moneylog.core.card.exception.CardExceptions.CardNotFoundException;
import com.moneylog.core.finance.app.cards.CardViews;
import com.moneylog.core.finance.app.cards.FutureInstallmentsView;
import com.moneylog.core.finance.app.cards.InvoiceDetails;
import com.moneylog.core.finance.app.cards.InvoiceViews;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.TimeProvider;

public class GetFutureInstallmentsUseCaseImpl implements GetFutureInstallmentsUseCase {

    private final FinancialRecordsGateway records;
    private final TimeProvider time;

    public GetFutureInstallmentsUseCaseImpl(FinancialRecordsGateway records, TimeProvider time) {
        this.records = records;
        this.time = time;
    }

    @Override
    public FutureInstallmentsView handle(GetFutureInstallmentsQuery query) {
        FinancialRecords loaded = records.load(query.userId());
        CreditCard card = loaded.cards().stream().filter(c -> c.id().equals(query.cardId())).findFirst()
                .orElseThrow(() -> new CardNotFoundException(query.cardId()));
        var transactions = InvoiceViews.index(loaded);
        var today = time.today();
        List<FutureInstallmentsView.ByInvoice> byInvoice = new ArrayList<>();
        Money total = Money.zero(CurrencyCode.BRL);
        for (CreditCardInvoice invoice : CardViews.chainOf(card, loaded).invoices()) {
            if (invoice.statusAt(today) != InvoiceStatus.FUTURE) {
                continue;
            }
            List<InvoiceDetails.Item> items = InvoiceViews.items(invoice.items(), transactions, true);
            if (items.isEmpty()) {
                continue;
            }
            Money amount = Money.sum(items.stream().map(InvoiceDetails.Item::amount).toList(), CurrencyCode.BRL);
            total = total.add(amount);
            byInvoice.add(new FutureInstallmentsView.ByInvoice(InvoiceIds.of(card.id(), invoice.closingDate()),
                    invoice.referenceMonth(), invoice.dueDate(), amount, items));
        }
        return new FutureInstallmentsView(total, byInvoice);
    }
}
