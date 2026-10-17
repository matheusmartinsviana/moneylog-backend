package com.moneylog.core.income.app;

import java.util.UUID;

import com.moneylog.core.sk.money.Money;

public record PayrollLoanApplication(UUID transactionId, UUID debtId, Money applied, Money interest, Money principal) {
}
