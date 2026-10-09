package com.moneylog.infra.recurrence.controller;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.recurrence.domain.Frequency;
import com.moneylog.core.recurrence.domain.IntervalUnit;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.infra.web.validation.DateInWindow;
import com.moneylog.infra.web.validation.NoControlChars;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Corpos de entrada de {@code /v1/recurrences} (contrato: {@code RecurrenceInput}, {@code RecurrenceUpdate}, ...). */
public final class RecurrenceRequests {

    public static final String AMOUNT_PATTERN = "^\\d{1,13}(\\.\\d{1,2})?$";

    private RecurrenceRequests() {
    }

    /** {@code RecurrenceType} do contrato: só os três tipos que uma recorrência pode gerar. */
    public enum RecurrenceType {
        EXPENSE,
        INCOME,
        CARD_PURCHASE
    }

    public record RecurrenceInput(@NotNull RecurrenceType type, @NotNull @Pattern(regexp = AMOUNT_PATTERN) String amount,
                                  @Size(max = 200) @NoControlChars String description, UUID categoryId, UUID accountId,
                                  PaymentMethod method, UUID cardId, @NotNull Frequency frequency, @Min(1) @Max(1000) Integer interval,
                                  IntervalUnit unit, @NotNull @DateInWindow LocalDate startDate, @DateInWindow LocalDate endDate,
                                  @Min(1) @Max(10000) Integer maxOccurrences,
                                  @Min(0) @Max(1) Integer incomeCompetenceOffsetMonths) {
    }

    public record RecurrenceUpdate(@NotNull @Pattern(regexp = AMOUNT_PATTERN) String amount,
                                   @Size(max = 200) @NoControlChars String description, @NotNull UUID categoryId,
                                   UUID accountId, PaymentMethod method, UUID cardId, @DateInWindow LocalDate endDate,
                                   @NotNull Long version, @Min(0) @Max(1) Integer incomeCompetenceOffsetMonths) {
    }

    public record EndInput(@DateInWindow LocalDate endDate) {
    }

    public record RealizeInput(@Pattern(regexp = AMOUNT_PATTERN) String amount, java.util.UUID accountId,
                               com.moneylog.core.transaction.domain.PaymentMethod method) {
    }
}
