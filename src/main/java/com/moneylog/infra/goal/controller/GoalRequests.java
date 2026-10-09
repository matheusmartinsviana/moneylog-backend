package com.moneylog.infra.goal.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.goal.domain.GoalActionFrequency;
import com.moneylog.core.goal.domain.GoalKind;
import com.moneylog.core.goal.domain.GoalMetric;
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

    /**
     * {@code kind} ausente = {@code SAVINGS}. Em {@code DEBT_PAYOFF} o {@code target} é ignorado e o {@code name} é opcional
     * (padrão "Quitar todas as dívidas"); em {@code SAVINGS} os dois são obrigatórios (conferido no controller). Em {@code METRIC}
     * (ADR-016) {@code metric} e {@code target} (pode ser 0.00) são obrigatórios e {@code name} é opcional; a baseline é capturada pelo servidor.
     */
    public record GoalInput(@Size(max = 100) @NoControlChars String name, @Pattern(regexp = AMOUNT_PATTERN) String target,
                            @DateInWindow LocalDate deadline, GoalPriority priority,
                            @Size(max = 500) @NoControlChars(allowLineBreaks = true) String description, GoalKind kind, GoalMetric metric) {
    }

    /**
     * {@code name} e {@code target} são obrigatórios só em {@code SAVINGS} (o caso de uso responde 422 se faltarem); em {@code METRIC} o nome
     * é opcional e o alvo ausente mantém o atual; em {@code DEBT_PAYOFF} ausentes mantêm os atuais. {@code planNote} ausente apaga o texto.
     */
    public record GoalUpdate(@Size(max = 100) @NoControlChars String name, @Pattern(regexp = AMOUNT_PATTERN) String target,
                             @DateInWindow LocalDate deadline, GoalPriority priority,
                             @Size(max = 500) @NoControlChars(allowLineBreaks = true) String description, @NotNull Long version,
                             @Size(max = 500) @NoControlChars(allowLineBreaks = true) String planNote) {
    }

    public record GoalActionInput(@NotBlank @Size(max = 100) @NoControlChars String title, @NotNull GoalActionFrequency frequency) {
    }

    /** {@code period}: MONTHLY {@code YYYY-MM}; WEEKLY {@code YYYY-Www}; ONE_TIME ausente ou {@code ONCE}. {@code done} ausente = true. */
    public record GoalCheckInInput(@Size(max = 8) String period, Boolean done) {
    }

    public record GoalReservationInput(@NotNull UUID accountId, @NotNull @Pattern(regexp = SIGNED_AMOUNT_PATTERN) String amount,
                                       @NotNull @DateInWindow LocalDate date,
                                       @Size(max = 200) @NoControlChars(allowLineBreaks = true) String note) {
    }

    /** {@code PUT /v1/goals/{id}/photos/order}: o conjunto exato de ids das fotos da meta, na nova ordem. */
    public record GoalPhotoOrderInput(@NotNull @Size(max = 100) List<@NotNull UUID> photoIds) {
    }

    /** {@code PUT /v1/goals/{id}/featured}. */
    public record GoalFeaturedInput(@NotNull Boolean featured) {
    }
}
