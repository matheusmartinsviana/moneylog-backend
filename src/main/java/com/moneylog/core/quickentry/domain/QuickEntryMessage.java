package com.moneylog.core.quickentry.domain;

import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.TransactionStatus;

public final class QuickEntryMessage {

    private QuickEntryMessage() {
    }

    public static String of(QuickEntryKind kind, Money amount, String categoryName, String accountName, TransactionStatus status) {
        boolean expense = kind == QuickEntryKind.EXPENSE;
        String head = (expense ? "Gasto" : "Entrada") + " de " + AmountText.formatBrl(amount) + " em " + categoryName + " (" + accountName + ") ";
        String registered = expense ? "registrado" : "registrada";
        if (status == TransactionStatus.VOIDED) {
            return head + registered + " e depois " + (expense ? "estornado." : "estornada.");
        }
        return head + registered + ".";
    }
}
