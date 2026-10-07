package com.moneylog.core.finance.app.cards.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.finance.app.cards.InvoiceDetails;
import com.moneylog.core.sk.money.Money;

public interface PayInvoiceUseCase {

    /** Escopo da chave de idempotência do pagamento: próprio, distinto do de criação de movimentação (S-87, T55). */
    String IDEMPOTENCY_SCOPE = "POST /v1/invoices/{id}/payments";

    /**
     * Paga a fatura: cria um {@code INVOICE_PAYMENT} ({@code CARD +X / ACCOUNT −X}, nenhum consumo) e devolve a fatura
     * atualizada. Qualquer valor &gt; 0 em fatura {@code OPEN}/{@code CLOSED}/{@code OVERDUE} (S-15, S-16): o excedente vira
     * crédito transportado. Trava o cartão e depois a conta (ADR-006 §5) e só então lê a cadeia de faturas.
     *
     * @param idempotencyKey a mesma chave e o mesmo comando devolvem o <b>estado atual</b> da fatura sem segundo pagamento;
     *                       com outro comando, {@code IDEMPOTENCY_KEY_REUSED}; {@code null} = sem idempotência (uso interno)
     * @throws com.moneylog.core.card.exception.CardExceptions.InvoiceNotFoundException fatura de outro usuário ou fora da janela
     * @throws com.moneylog.core.card.exception.CardExceptions.InvalidInvoiceOperationException fatura {@code FUTURE}/{@code PAID}
     */
    InvoiceDetails handle(PayInvoiceCommand command, UUID idempotencyKey);

    default InvoiceDetails handle(PayInvoiceCommand command) {
        return handle(command, null);
    }

    record PayInvoiceCommand(UUID userId, UUID invoiceId, UUID accountId, Money amount, LocalDate date) {
    }
}
