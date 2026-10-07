package com.moneylog.core.card.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Id determinístico da fatura (ADR-006, S-119): UUID v5 (RFC 4122 §4.3) de {@code cardId|closingDate} sob um namespace
 * fixo. O mesmo par {@code (cartão, fechamento)} dá sempre o mesmo id, com ou sem linha gravada, então a fatura que
 * ainda não recebeu item (a corrente vazia) já tem id para a URL. É de mão única: do id não se extrai o par.
 *
 * <p>Função pura: o domínio continua falando em {@code closingDate}; só leitura e gravação precisam do id.
 */
public final class InvoiceIds {

    /** Namespace fixo da ADR-006: nunca muda (mudar invalida todos os ids). */
    public static final UUID NAMESPACE = UUID.fromString("c0a8f5d2-6e1b-4a73-9d54-2f8e7b1a3c60");

    private InvoiceIds() {
    }

    public static UUID of(UUID cardId, LocalDate closingDate) {
        Objects.requireNonNull(cardId, "cardId");
        Objects.requireNonNull(closingDate, "closingDate");
        byte[] name = (cardId.toString().toLowerCase(java.util.Locale.ROOT) + "|" + closingDate).getBytes(StandardCharsets.UTF_8);
        ByteBuffer namespace = ByteBuffer.allocate(16).putLong(NAMESPACE.getMostSignificantBits())
                .putLong(NAMESPACE.getLeastSignificantBits());
        byte[] hash;
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(namespace.array());
            sha1.update(name);
            hash = sha1.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 indisponível", e);
        }
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x50); // versão 5
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80); // variante RFC 4122
        ByteBuffer bytes = ByteBuffer.wrap(hash, 0, 16);
        return new UUID(bytes.getLong(), bytes.getLong());
    }
}
