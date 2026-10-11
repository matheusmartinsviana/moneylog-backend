package com.moneylog.infra.debt.controller;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.infra.web.validation.DateInWindow;
import com.moneylog.infra.web.validation.NoControlChars;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class DebtRequests {

    public static final String AMOUNT_PATTERN = "^\\d{1,13}(\\.\\d{1,2})?$";

    private DebtRequests() {
    }

    public record DebtInput(UUID personId, @Size(max = 100) @NoControlChars String creditorName, @NotNull DebtDirection direction,
                            @NotNull @Pattern(regexp = AMOUNT_PATTERN) String principal, @NotNull @DateInWindow LocalDate date,
                            @DateInWindow LocalDate expectedDate, @Size(max = 200) @NoControlChars String description,
                            UUID categoryId) {
    }

    public record DebtUpdate(@DateInWindow LocalDate expectedDate, @Size(max = 200) @NoControlChars String description,
                             @NotNull Long version) {
    }

    public record ReasonInput(@Size(max = 500) @NoControlChars String reason) {
    }
}
