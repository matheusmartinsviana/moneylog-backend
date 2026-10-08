package com.moneylog.core.thirdparty.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Marca de que uma despesa (compra no cartão, inclusive parcelada, ou despesa em conta) é de uma pessoa (responsável real), embora paga
 * pelo usuário. Não gera posting: o consumo continua do usuário até o reembolso (um {@code REFUND} ligado à compra o neutraliza).
 * Compra parcelada: a marca vale para a compra inteira, então as parcelas futuras também são de terceiro. {@code amount} (opcional) é a parte
 * reembolsável já comprovada; nulo = o valor cobrado da compra.
 */
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
