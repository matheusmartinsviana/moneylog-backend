package com.moneylog.core.thirdparty.domain;

import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.sk.time.Competence;

public record ReceivableExpectedMonth(UUID transactionId, UUID installmentId, UUID personId, Competence month, long version) {

    public ReceivableExpectedMonth {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(installmentId, "installmentId");
        Objects.requireNonNull(personId, "personId");
        Objects.requireNonNull(month, "month");
    }
}
