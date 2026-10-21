package com.moneylog.infra.quickentry.controller;

import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.annotation.JsonDeserialize;

import com.moneylog.core.quickentry.app.CreateQuickEntryCommand;
import com.moneylog.core.quickentry.domain.QuickAmountInput;
import com.moneylog.core.quickentry.domain.QuickEntryKind;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.infra.web.validation.NoControlChars;

public record QuickEntryRequest(@NotNull QuickEntryKind kind,
                                @NotNull @JsonDeserialize(using = QuickAmountJson.class) QuickAmountInput amount,
                                @Size(min = 1, max = 60) @NoControlChars String category,
                                @Size(max = 200) @NoControlChars String description, PaymentMethod method, LocalDate date) {

    public static final int AMOUNT_MAX_LENGTH = 40;

    public boolean methodOnIncome() {
        return kind == QuickEntryKind.INCOME && method != null;
    }

    public boolean amountOutOfSize() {
        String raw = amount.raw();
        return raw == null || raw.isEmpty() || raw.length() > AMOUNT_MAX_LENGTH;
    }

    public CreateQuickEntryCommand toCommand(UUID userId, UUID tokenId, UUID accountId, String idempotencyKey, String maskedIp) {
        return new CreateQuickEntryCommand(userId, tokenId, accountId, idempotencyKey, kind, amount, category, description, method, date,
                maskedIp);
    }
}
