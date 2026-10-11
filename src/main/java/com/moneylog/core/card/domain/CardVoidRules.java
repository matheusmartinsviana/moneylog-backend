package com.moneylog.core.card.domain;

import java.time.LocalDate;
import java.util.Collection;

import com.moneylog.core.sk.ledger.VoidDecision;

public final class CardVoidRules {

    public static final String TRANSACTION_IN_CLOSED_INVOICE = "TRANSACTION_IN_CLOSED_INVOICE";

    private CardVoidRules() {
    }

    public static VoidDecision forPurchase(Collection<TransactionInstallment> installments, InvoiceChain chain, LocalDate today) {
        boolean inClosedInvoice = installments.stream()
                .filter(i -> !i.isCanceled())
                .anyMatch(i -> chain.statusOf(i.invoiceClosingDate(), today).isClosed());
        return inClosedInvoice
                ? VoidDecision.refuse(TRANSACTION_IN_CLOSED_INVOICE,
                        "há parcela em fatura fechada; cancele as parcelas restantes e registre um reembolso no cartão.")
                : VoidDecision.allow();
    }

    public static VoidDecision forCardRefund(LocalDate invoiceClosingDate, InvoiceChain chain, LocalDate today) {
        return chain.statusOf(invoiceClosingDate, today).isClosed()
                ? VoidDecision.refuse(TRANSACTION_IN_CLOSED_INVOICE,
                        "o crédito está em fatura fechada; se o banco desfez o estorno, registre uma nova compra.")
                : VoidDecision.allow();
    }

    public static VoidDecision forInvoicePayment() {
        return VoidDecision.allow();
    }
}
