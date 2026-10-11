package com.moneylog.core.finance.app.cards;

import java.time.YearMonth;
import java.util.List;

import com.moneylog.core.card.domain.InvoiceStatus;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

import java.time.LocalDate;
import java.util.UUID;

public record PurchasePreviewView(List<Installment> installments, Money total, Money availableLimitAfter, boolean overLimit,
                                  List<Warning> warnings) {

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
