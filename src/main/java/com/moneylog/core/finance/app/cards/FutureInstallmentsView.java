package com.moneylog.core.finance.app.cards;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;

public record FutureInstallmentsView(Money total, List<ByInvoice> byInvoice) {

    public FutureInstallmentsView {
        byInvoice = List.copyOf(byInvoice);
    }

    public record ByInvoice(UUID invoiceId, YearMonth referenceMonth, LocalDate dueDate, Money amount,
                            List<InvoiceDetails.Item> items) {
        public ByInvoice {
            items = List.copyOf(items);
        }
    }
}
