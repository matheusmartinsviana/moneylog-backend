package com.moneylog.core.quickentry.app;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.quickentry.domain.QuickAmountInput;
import com.moneylog.core.quickentry.domain.QuickEntryKind;
import com.moneylog.core.transaction.domain.PaymentMethod;

public record CreateQuickEntryCommand(UUID userId, UUID tokenId, UUID accountId, String idempotencyKey, QuickEntryKind kind,
                                      QuickAmountInput amount, String category, String description, PaymentMethod method,
                                      LocalDate date, String maskedIp) {

    public CreateQuickEntryCommand {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(tokenId, "tokenId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(amount, "amount");
        if (kind == QuickEntryKind.INCOME && method != null) {
            throw new IllegalArgumentException("method só vale para gasto");
        }
    }
}
