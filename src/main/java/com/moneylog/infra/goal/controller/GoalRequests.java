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

public final class GoalRequests {

    public static final String AMOUNT_PATTERN = "^\\d{1,13}(\\.\\d{1,2})?$";
    public static final String SIGNED_AMOUNT_PATTERN = "^-?\\d{1,13}(\\.\\d{1,2})?$";

    private GoalRequests() {
    }

    public record GoalInput(@Size(max = 100) @NoControlChars String name, @Pattern(regexp = AMOUNT_PATTERN) String target,
                            @DateInWindow LocalDate deadline, GoalPriority priority,
                            @Size(max = 500) @NoControlChars(allowLineBreaks = true) String description, GoalKind kind, GoalMetric metric) {
    }

    public record GoalUpdate(@Size(max = 100) @NoControlChars String name, @Pattern(regexp = AMOUNT_PATTERN) String target,
                             @DateInWindow LocalDate deadline, GoalPriority priority,
                             @Size(max = 500) @NoControlChars(allowLineBreaks = true) String description, @NotNull Long version,
                             @Size(max = 500) @NoControlChars(allowLineBreaks = true) String planNote) {
    }

    public record GoalActionInput(@NotBlank @Size(max = 100) @NoControlChars String title, @NotNull GoalActionFrequency frequency) {
    }

    public record GoalCheckInInput(@Size(max = 8) String period, Boolean done) {
    }

    public record GoalReservationInput(@NotNull UUID accountId, @NotNull @Pattern(regexp = SIGNED_AMOUNT_PATTERN) String amount,
                                       @NotNull @DateInWindow LocalDate date,
                                       @Size(max = 200) @NoControlChars(allowLineBreaks = true) String note) {
    }

    public record GoalPhotoOrderInput(@NotNull @Size(max = 100) List<@NotNull UUID> photoIds) {
    }

    public record GoalFeaturedInput(@NotNull Boolean featured) {
    }
}
