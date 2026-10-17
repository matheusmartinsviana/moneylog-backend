package com.moneylog.infra.card.controller;

import java.time.LocalDate;
import java.util.List;

import com.moneylog.core.transaction.domain.SplitMode;
import com.moneylog.infra.transaction.controller.OriginalAmountInput;
import com.moneylog.infra.transaction.controller.PersonShareInput;
import com.moneylog.infra.web.validation.DateInWindow;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PurchasePreviewRequest(@NotNull @Pattern(regexp = "^\\d{1,13}(\\.\\d{1,2})?$") String amount,
                                     @NotNull @Min(1) @Max(36) Integer installments, @NotNull @DateInWindow LocalDate date,
                                     SplitMode splitMode, @Size(max = 20) @Valid List<@NotNull PersonShareInput> shares,
                                     @Valid OriginalAmountInput originalAmount,
                                     @Pattern(regexp = OriginalAmountInput.FX_RATE_PATTERN) String fxRate,
                                     @Min(0) @Max(10000) Integer iofBps) {
}
