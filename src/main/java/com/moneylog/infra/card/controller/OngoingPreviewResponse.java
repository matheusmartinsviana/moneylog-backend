package com.moneylog.infra.card.controller;

import java.time.LocalDate;

import com.moneylog.core.finance.app.cards.OngoingPreviewView;
import com.moneylog.core.sk.money.Money;

public record OngoingPreviewResponse(int remainingInstallments, Money installmentAmount, Money remaining, LocalDate firstDueDate,
                                     LocalDate lastDueDate, PurchasePreviewResponse purchase) {

    public static OngoingPreviewResponse of(OngoingPreviewView view) {
        return new OngoingPreviewResponse(view.remainingInstallments(), view.installmentAmount(), view.remaining(), view.firstDueDate(),
                view.lastDueDate(), PurchasePreviewResponse.of(view.purchase()));
    }
}
