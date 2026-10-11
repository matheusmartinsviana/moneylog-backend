package com.moneylog.core.thirdparty.domain;

import java.util.Objects;
import java.util.UUID;

public record ThirdPartyExpense(UUID id, UUID userId, UUID transactionId, UUID personId, String note, com.moneylog.core.sk.money.Money amount) {

    public static final int NOTE_MAX_LENGTH = 200;

    public ThirdPartyExpense {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(personId, "personId");
        if (note != null && note.length() > NOTE_MAX_LENGTH) {
            throw new IllegalArgumentException("nota longa demais");
        }
    }
}
