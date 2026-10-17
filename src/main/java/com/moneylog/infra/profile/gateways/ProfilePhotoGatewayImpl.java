package com.moneylog.infra.profile.gateways;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.moneylog.core.goal.app.gateways.PhotoStorage.Variant;
import com.moneylog.core.goal.photo.ProcessedPhoto;
import com.moneylog.core.profile.app.gateways.ProfilePhotoGateway;

public class ProfilePhotoGatewayImpl implements ProfilePhotoGateway {

    private final JdbcTemplate jdbc;

    public ProfilePhotoGatewayImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void replace(UUID userId, ProcessedPhoto photo, Instant now) {
        jdbc.update("INSERT INTO user_profile_photos (user_id, display, thumbnail, width, height, size_bytes, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)"
                + " ON CONFLICT (user_id) DO UPDATE SET display = EXCLUDED.display, thumbnail = EXCLUDED.thumbnail, width = EXCLUDED.width,"
                + " height = EXCLUDED.height, size_bytes = EXCLUDED.size_bytes, updated_at = GREATEST(EXCLUDED.updated_at, user_profile_photos.updated_at + INTERVAL '1 millisecond')",
                userId, photo.display(), photo.thumbnail(), photo.width(), photo.height(), photo.sizeBytes(), Timestamp.from(now));
    }

    @Override
    public Optional<byte[]> content(UUID userId, Variant variant) {
        String column = variant == Variant.THUMBNAIL ? "thumbnail" : "display";
        return jdbc.query("SELECT " + column + " FROM user_profile_photos WHERE user_id = ?", (rs, row) -> rs.getBytes(1), userId).stream().findFirst();
    }

    @Override
    public Optional<Instant> updatedAt(UUID userId) {
        return jdbc.query("SELECT updated_at FROM user_profile_photos WHERE user_id = ?", (rs, row) -> rs.getTimestamp(1).toInstant(), userId)
                .stream().findFirst();
    }

    @Override
    public void delete(UUID userId) {
        jdbc.update("DELETE FROM user_profile_photos WHERE user_id = ?", userId);
    }
}
