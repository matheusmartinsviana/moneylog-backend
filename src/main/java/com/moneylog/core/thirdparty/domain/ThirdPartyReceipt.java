package com.moneylog.core.thirdparty.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;

public record ThirdPartyReceipt(UUID id, UUID userId, UUID personId, UUID transactionId, Money amount, LocalDate receivedOn, String note) {

    public ThirdPartyReceipt {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(personId, "personId");
        if (amount == null || !amount.isPositive()) {
            throw new com.moneylog.core.thirdparty.exception.ThirdPartyExceptions.ThirdPartyExpenseInvalidException("o valor recebido precisa ser maior que zero.");
        }
        com.moneylog.core.sk.money.AmountLimit.ensureWithinLimit(amount, "amount");
        if (note != null && note.length() > 200) {
            throw new com.moneylog.core.thirdparty.exception.ThirdPartyExceptions.ThirdPartyExpenseInvalidException("a nota tem até 200 caracteres.");
        }
    }
}
