package com.moneylog.core.card.domain;

import java.time.LocalDate;
import java.util.Collection;

import com.moneylog.core.sk.ledger.VoidDecision;

/**
 * Guardas de estorno das movimentações de cartão (D1 §2.4, regra 1, S-36). Usam a mesma função de status da fatura
 * ({@link InvoiceChain#statusOf}) com o "hoje" do relógio: a mesma compra é estornável antes do fechamento e não
 * depois. A adaptação para a porta {@code TransactionVoidPolicy} fica no pacote de movimentação.
 */
public final class CardVoidRules {

    public static final String TRANSACTION_IN_CLOSED_INVOICE = "TRANSACTION_IN_CLOSED_INVOICE";

    private CardVoidRules() {
    }

    /**
     * Compra: só se nenhuma parcela (não cancelada) estiver em fatura fechada ({@code CLOSED}/{@code OVERDUE}/
     * {@code PAID}). A correção é cancelar as parcelas restantes + reembolso no cartão.
     */
    public static VoidDecision forPurchase(Collection<TransactionInstallment> installments, InvoiceChain chain, LocalDate today) {
        boolean inClosedInvoice = installments.stream()
                .filter(i -> !i.isCanceled())
                .anyMatch(i -> chain.statusOf(i.invoiceClosingDate(), today).isClosed());
        return inClosedInvoice
                ? VoidDecision.refuse(TRANSACTION_IN_CLOSED_INVOICE,
                        "há parcela em fatura fechada; cancele as parcelas restantes e registre um reembolso no cartão.")
                : VoidDecision.allow();
    }

    /** Reembolso no cartão: só se o crédito está em fatura {@code FUTURE}/{@code OPEN}. */
    public static VoidDecision forCardRefund(LocalDate invoiceClosingDate, InvoiceChain chain, LocalDate today) {
        return chain.statusOf(invoiceClosingDate, today).isClosed()
                ? VoidDecision.refuse(TRANSACTION_IN_CLOSED_INVOICE,
                        "o crédito está em fatura fechada; se o banco desfez o estorno, registre uma nova compra.")
                : VoidDecision.allow();
    }

    /** Pagamento de fatura: sempre estornável; o status da fatura é recalculado (é derivado). */
    public static VoidDecision forInvoicePayment() {
        return VoidDecision.allow();
    }
}
