package com.moneylog.core.finance.app.cards;

import java.time.YearMonth;
import java.util.List;

import com.moneylog.core.card.domain.InvoiceStatus;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.PersonAmount;
import com.moneylog.core.transaction.domain.ShareLine;
import com.moneylog.core.transaction.domain.SplitMode;

import java.time.LocalDate;
import java.util.UUID;

public record PurchasePreviewView(List<Installment> installments, Money total, Money availableLimitAfter, boolean overLimit,
                                  List<Warning> warnings, SplitMode splitMode, List<ShareLine> shares) {

    public enum Warning {
        LATE_ENTRY,
        OVER_LIMIT,
        FUTURE_DATE,
        FINANCING_PENDING
    }

    public record InvoiceTarget(UUID id, YearMonth referenceMonth, LocalDate closingDate, LocalDate dueDate, InvoiceStatus status) {
    }

    public record Installment(int number, int total, Money amount, Competence competence, InvoiceTarget invoice, boolean lateEntry,
                              Money myAmount, Money thirdPartyAmount, List<PersonAmount> byPerson) {

        public Installment {
            byPerson = List.copyOf(byPerson);
        }

        public Installment(int number, int total, Money amount, Competence competence, InvoiceTarget invoice, boolean lateEntry) {
            this(number, total, amount, competence, invoice, lateEntry, amount, Money.zero(amount.currency()), List.of());
        }
    }

    public PurchasePreviewView(List<Installment> installments, Money total, Money availableLimitAfter, boolean overLimit,
                               List<Warning> warnings) {
        this(installments, total, availableLimitAfter, overLimit, warnings, null, List.of());
    }

    public PurchasePreviewView {
        installments = List.copyOf(installments);
        warnings = List.copyOf(warnings);
        shares = shares == null ? List.of() : List.copyOf(shares);
    }
}
