package com.moneylog.infra.recurrence.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.moneylog.core.recurrence.app.ForeignInput;
import com.moneylog.core.recurrence.app.SubscriptionInput;
import com.moneylog.core.recurrence.domain.Frequency;
import com.moneylog.core.recurrence.domain.IntervalUnit;
import com.moneylog.core.recurrence.domain.RecurrenceScope;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.SplitMode;
import com.moneylog.infra.transaction.controller.OriginalAmountInput;
import com.moneylog.infra.transaction.controller.PersonShareInput;
import com.moneylog.infra.web.ProvenanceInput;
import com.moneylog.infra.web.validation.DateInWindow;
import com.moneylog.infra.web.validation.NoControlChars;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class RecurrenceRequests {

    public static final String AMOUNT_PATTERN = "^\\d{1,13}(\\.\\d{1,2})?$";

    private RecurrenceRequests() {
    }

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
                                  @Min(0) @Max(1) Integer incomeCompetenceOffsetMonths, String nature,
                                  SplitMode splitMode, @Valid @Size(max = 20) List<PersonShareInput> shares,
                                  @Valid OriginalAmountInput originalAmount,
                                  @Pattern(regexp = OriginalAmountInput.FX_RATE_PATTERN) String referenceFxRate,
                                  @Min(0) @Max(10000) Integer iofBps, @JsonProperty("isSubscription") Boolean isSubscription, RecurrenceScope scope,
                                  @Size(max = 60) @NoControlChars String channel, @Size(max = 60) @NoControlChars String overlapGroup,
                                  @DateInWindow LocalDate lastUsedOn, @Valid ProvenanceInput provenance) {

        public ForeignInput foreign() {
            return OriginalAmountInput.foreign(originalAmount, referenceFxRate, iofBps);
        }

        public SubscriptionInput subscription() {
            return new SubscriptionInput(isSubscription, scope, channel, overlapGroup, lastUsedOn);
        }
    }

    public record RecurrenceUpdate(@NotNull @Pattern(regexp = AMOUNT_PATTERN) String amount,
                                   @Size(max = 200) @NoControlChars String description, @NotNull UUID categoryId,
                                   UUID accountId, PaymentMethod method, UUID cardId, @DateInWindow LocalDate endDate,
                                   @NotNull Long version, @Min(0) @Max(1) Integer incomeCompetenceOffsetMonths,
                                   String nature, SplitMode splitMode, @Valid @Size(max = 20) List<PersonShareInput> shares,
                                   @Valid OriginalAmountInput originalAmount,
                                   @Pattern(regexp = OriginalAmountInput.FX_RATE_PATTERN) String referenceFxRate,
                                   @Min(0) @Max(10000) Integer iofBps, @JsonProperty("isSubscription") Boolean isSubscription, RecurrenceScope scope,
                                   @Size(max = 60) @NoControlChars String channel, @Size(max = 60) @NoControlChars String overlapGroup,
                                   @DateInWindow LocalDate lastUsedOn, @Valid ProvenanceInput provenance) {

        public ForeignInput foreign() {
            return OriginalAmountInput.foreign(originalAmount, referenceFxRate, iofBps);
        }

        public SubscriptionInput subscription() {
            return new SubscriptionInput(isSubscription, scope, channel, overlapGroup, lastUsedOn);
        }
    }

    public record EndInput(@DateInWindow LocalDate endDate, @DateInWindow LocalDate activeUntil) {
    }

    public record RealizeInput(@Pattern(regexp = AMOUNT_PATTERN) String amount, java.util.UUID accountId,
                               com.moneylog.core.transaction.domain.PaymentMethod method, @Valid OriginalAmountInput originalAmount,
                               @Pattern(regexp = OriginalAmountInput.FX_RATE_PATTERN) String fxRate,
                               @Min(0) @Max(10000) Integer iofBps) {

        public ForeignInput foreign() {
            return OriginalAmountInput.foreign(originalAmount, fxRate, iofBps);
        }
    }
}
