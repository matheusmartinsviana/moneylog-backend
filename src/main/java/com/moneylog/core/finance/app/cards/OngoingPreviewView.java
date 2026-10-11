package com.moneylog.core.finance.app.cards;

import java.time.LocalDate;
import java.util.List;

import com.moneylog.core.sk.money.Money;

public record OngoingPreviewView(int remainingInstallments, Money installmentAmount, Money remaining, LocalDate firstDueDate,
                                 LocalDate lastDueDate, PurchasePreviewView purchase) {

    public OngoingPreviewView {
        java.util.Objects.requireNonNull(purchase, "purchase");
    }

    public List<PurchasePreviewView.Installment> installments() {
        return purchase.installments();
    }
}
