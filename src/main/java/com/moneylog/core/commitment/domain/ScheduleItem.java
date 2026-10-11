package com.moneylog.core.commitment.domain;

import java.time.LocalDate;
import java.util.Objects;

import com.moneylog.core.commitment.exception.CommitmentExceptions.InvalidCommitmentException;
import com.moneylog.core.sk.money.AmountLimit;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.FinancialDates;

public record ScheduleItem(int position, String label, ScheduleItemType type, LocalDate dueDate, Money amount, boolean paid, LocalDate paidOn) {

    public static final int LABEL_MAX_LENGTH = 100;

    public ScheduleItem {
        Objects.requireNonNull(type, "type");
        if (position < 1) {
            throw new InvalidCommitmentException("a posição do item começa em 1.");
        }
        if (label == null || label.isBlank() || label.strip().length() > LABEL_MAX_LENGTH) {
            throw new InvalidCommitmentException("o rótulo do item é obrigatório e tem até " + LABEL_MAX_LENGTH + " caracteres.");
        }
        label = label.strip();
        if (amount == null || !amount.isPositive()) {
            throw new InvalidCommitmentException("o valor do item precisa ser maior que zero.");
        }
        AmountLimit.ensureWithinLimit(amount, "amount");
        FinancialDates.requireInWindow(dueDate, "dueDate");
        FinancialDates.requireInWindow(paidOn, "paidOn");
        if (paidOn != null && !paid) {
            throw new InvalidCommitmentException("item com data de pagamento precisa estar pago.");
        }
    }

    public boolean isPaid() {
        return paid;
    }

    public ScheduleItem markPaid(LocalDate date) {
        return new ScheduleItem(position, label, type, dueDate, amount, true, date);
    }
}
