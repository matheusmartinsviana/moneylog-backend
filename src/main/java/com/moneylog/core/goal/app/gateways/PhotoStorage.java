package com.moneylog.core.goal.app.gateways;

import java.util.Optional;
import java.util.UUID;

public interface PhotoStorage {

    enum Variant {
        DISPLAY, THUMBNAIL
    }

    void put(UUID userId, UUID goalId, UUID photoId, byte[] display, byte[] thumbnail);

    Optional<byte[]> get(UUID userId, UUID goalId, UUID photoId, Variant variant);

    void delete(UUID userId, UUID photoId);
}
