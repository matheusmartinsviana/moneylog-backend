package com.moneylog.infra.purchasegroup.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.purchasegroup.app.usecase.CreatePurchaseGroupUseCase.MemberSpec;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.SplitMode;
import com.moneylog.infra.transaction.controller.PersonShareInput;
import com.moneylog.infra.web.validation.DateInWindow;
import com.moneylog.infra.web.validation.NoControlChars;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class PurchaseGroupRequests {

    public static final String AMOUNT_PATTERN = "^\\d{1,13}(\\.\\d{1,2})?$";

    private PurchaseGroupRequests() {
    }

    public record MemberCreate(@NotNull UUID cardId, @NotNull @Min(2) @Max(36) Integer totalInstallments,
                               @NotNull @Pattern(regexp = AMOUNT_PATTERN) String installmentAmount,
                               @Min(1) @Max(36) Integer remainingInstallments, @NotNull @DateInWindow LocalDate firstInvoiceDate,
                               UUID categoryId, SplitMode splitMode, @Size(max = 20) @Valid List<@NotNull PersonShareInput> shares) {

        MemberSpec toSpec() {
            return new MemberSpec(cardId, totalInstallments, Money.of(installmentAmount, CurrencyCode.BRL), remainingInstallments,
                    firstInvoiceDate, categoryId, splitMode, PersonShareInput.lines(shares));
        }
    }

    public record PurchaseGroupInput(@NotNull @Size(min = 1, max = 100) @NoControlChars String name, @NotNull Boolean interest,
                                     @Pattern(regexp = AMOUNT_PATTERN) String declaredTotal,
                                     @NotNull @Size(min = 1, max = 10) @Valid List<@NotNull @Valid MemberCreate> members) {

        Money declared() {
            return declaredTotal == null ? null : Money.of(declaredTotal, CurrencyCode.BRL);
        }

        List<MemberSpec> specs() {
            return members.stream().map(MemberCreate::toSpec).toList();
        }
    }

    public record PurchaseGroupUpdate(@NotNull @Size(min = 1, max = 100) @NoControlChars String name, @NotNull Boolean interest,
                                      @NotNull Long version) {
    }

    public record PurchaseGroupMemberInput(@NotNull UUID transactionId) {
    }
}
