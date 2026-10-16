package com.moneylog.infra.transaction.controller;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.ShareLine;

import com.moneylog.infra.web.ProvenanceInput;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.groups.Default;

public record PersonShareInput(UUID personId,
                               @Min(value = 1, groups = {Default.class, TransactionInput.Strict.class})
                               @Max(value = 10000, groups = {Default.class, TransactionInput.Strict.class}) Integer bps,
                               @Pattern(regexp = TransactionInput.AMOUNT_PATTERN, groups = {Default.class, TransactionInput.Strict.class})
                               String amount, @Valid ProvenanceInput provenance) {

    public ShareLine toLine() {
        return new ShareLine(personId, bps, amount == null ? null : Money.of(amount, CurrencyCode.BRL),
                ProvenanceInput.toDomain(provenance));
    }

    public static List<ShareLine> lines(List<PersonShareInput> inputs) {
        return inputs == null ? List.of() : inputs.stream().map(PersonShareInput::toLine).toList();
    }
}
