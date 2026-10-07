package com.moneylog.core.finance.app.cards;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.card.domain.InvoiceItemKind;
import com.moneylog.core.sk.money.Money;

/**
 * Fatura com os itens e pagamentos ativos (D2 §4.3 {@code Invoice}). Item estornado ou cancelado e pagamento estornado
 * não aparecem: o total da fatura já os desconta.
 */
public record InvoiceDetails(InvoiceView invoice, List<Item> items, List<Payment> payments) {

    public InvoiceDetails {
        Objects.requireNonNull(invoice, "invoice");
        items = List.copyOf(items);
        payments = List.copyOf(payments);
    }

    /**
     * @param number            número da parcela ({@code INSTALLMENT}); {@code null} no crédito
     * @param totalInstallments total de parcelas da compra; {@code null} no crédito
     * @param amount            sempre positivo; o sinal vem do {@code kind}
     */
    public record Item(InvoiceItemKind kind, UUID transactionId, String description, Integer number, Integer totalInstallments,
                       Money amount, LocalDate date, boolean lateEntry) {
    }

    /** @param transactionId a movimentação {@code INVOICE_PAYMENT} (é a que se estorna) */
    public record Payment(UUID transactionId, UUID accountId, Money amount, LocalDate date) {
    }
}
