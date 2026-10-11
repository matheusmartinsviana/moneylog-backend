package com.moneylog.core.card.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public final class InvoiceIds {

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
        hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
        hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
        ByteBuffer bytes = ByteBuffer.wrap(hash, 0, 16);
        return new UUID(bytes.getLong(), bytes.getLong());
    }
}
