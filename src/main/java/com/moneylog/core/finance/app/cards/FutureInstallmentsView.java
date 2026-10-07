package com.moneylog.core.finance.app.cards;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;

/**
 * Parcelas futuras de um cartão por fatura (D2 §4.3): as parcelas não canceladas das faturas {@code FUTURE}. {@code total}
 * é a soma do backend e é o {@code Card.committedFuture}.
 */
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
