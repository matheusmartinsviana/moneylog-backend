package com.moneylog.infra.quickentry.controller;

import java.util.UUID;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.moneylog.core.quickentry.domain.QuickEntryToken;

public final class QuickEntryTokenRequests {

    private QuickEntryTokenRequests() {
    }

    public record QuickEntryTokenInput(@NotBlank @Size(max = QuickEntryToken.MAX_LABEL_LENGTH) String label, @NotNull UUID accountId,
                                       @Min(1) @Max(90) Integer expiresInDays) {
    }

    public record QuickEntryTokenRename(@NotBlank @Size(max = QuickEntryToken.MAX_LABEL_LENGTH) String label) {
    }
}
