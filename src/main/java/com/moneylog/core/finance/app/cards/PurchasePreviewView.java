package com.moneylog.core.finance.app.cards;

import java.time.YearMonth;
import java.util.List;

import com.moneylog.core.card.domain.InvoiceStatus;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Prévia de uma compra no cartão (S-124): o que a criação gravaria, sem gravar. Os valores são os da fábrica do domínio
 * ({@code CardTransactions.purchase}), então a tela nunca calcula parcela, competência nem fatura.
 */
public record PurchasePreviewView(List<Installment> installments, Money total, Money availableLimitAfter, boolean overLimit,
                                  List<Warning> warnings) {

    /** Avisos da prévia; a UI avisa, nunca bloqueia (S-125). */
    public enum Warning {
        LATE_ENTRY,
        OVER_LIMIT,
        FUTURE_DATE
    }

    public record InvoiceTarget(UUID id, YearMonth referenceMonth, LocalDate closingDate, LocalDate dueDate, InvoiceStatus status) {
    }

    public record Installment(int number, int total, Money amount, Competence competence, InvoiceTarget invoice, boolean lateEntry) {
    }

    public PurchasePreviewView {
        installments = List.copyOf(installments);
        warnings = List.copyOf(warnings);
    }
}
