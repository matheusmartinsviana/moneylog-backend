package com.moneylog.core.profile.app.gateways;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.goal.app.gateways.PhotoStorage.Variant;
import com.moneylog.core.goal.photo.ProcessedPhoto;

public interface ProfilePhotoGateway {


    void replace(UUID userId, ProcessedPhoto photo, Instant now);

    Optional<byte[]> content(UUID userId, Variant variant);

    Optional<Instant> updatedAt(UUID userId);

    void delete(UUID userId);
}
