package com.moneylog.infra.goal.controller;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.goal.domain.GoalPriority;
import com.moneylog.infra.web.validation.DateInWindow;
import com.moneylog.infra.web.validation.NoControlChars;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Corpos de entrada de {@code /v1/goals} (contrato: {@code GoalInput}, {@code GoalUpdate}, {@code GoalReservationInput}). */
public final class GoalRequests {

    /** Valor positivo da meta: {@code AmountInput}. */
    public static final String AMOUNT_PATTERN = "^\\d{1,13}(\\.\\d{1,2})?$";
    /** Valor da reserva: com sinal (negativo = liberação). */
    public static final String SIGNED_AMOUNT_PATTERN = "^-?\\d{1,13}(\\.\\d{1,2})?$";

    private GoalRequests() {
    }

    public record GoalInput(@NotBlank @Size(max = 100) @NoControlChars String name,
                            @NotNull @Pattern(regexp = AMOUNT_PATTERN) String target, @DateInWindow LocalDate deadline,
                            GoalPriority priority,
                            @Size(max = 500) @NoControlChars(allowLineBreaks = true) String description) {
    }

    public record GoalUpdate(@NotBlank @Size(max = 100) @NoControlChars String name,
                             @NotNull @Pattern(regexp = AMOUNT_PATTERN) String target, @DateInWindow LocalDate deadline,
                             GoalPriority priority,
                             @Size(max = 500) @NoControlChars(allowLineBreaks = true) String description, @NotNull Long version) {
    }

    public record GoalReservationInput(@NotNull UUID accountId, @NotNull @Pattern(regexp = SIGNED_AMOUNT_PATTERN) String amount,
                                       @NotNull @DateInWindow LocalDate date,
                                       @Size(max = 200) @NoControlChars(allowLineBreaks = true) String note) {
    }
}
