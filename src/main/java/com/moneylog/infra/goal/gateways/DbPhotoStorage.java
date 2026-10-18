package com.moneylog.infra.goal.gateways;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.moneylog.core.goal.app.gateways.PhotoStorage;

public class DbPhotoStorage implements PhotoStorage {

    private static final String SELECT_DISPLAY = "SELECT b.display FROM goal_photo_blobs b JOIN goal_photos p ON p.id = b.photo_id AND p.user_id = b.user_id"
            + " WHERE p.id = ? AND p.user_id = ? AND p.goal_id = ?";
    private static final String SELECT_THUMBNAIL = "SELECT b.thumbnail FROM goal_photo_blobs b JOIN goal_photos p ON p.id = b.photo_id AND p.user_id = b.user_id"
            + " WHERE p.id = ? AND p.user_id = ? AND p.goal_id = ?";

    private final JdbcTemplate jdbc;

    public DbPhotoStorage(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void put(UUID userId, UUID goalId, UUID photoId, byte[] display, byte[] thumbnail) {
        int inserted = jdbc.update("INSERT INTO goal_photo_blobs (photo_id, user_id, display, thumbnail) SELECT p.id, p.user_id, ?, ? FROM goal_photos p"
                + " WHERE p.id = ? AND p.user_id = ? AND p.goal_id = ?", display, thumbnail, photoId, userId, goalId);
        if (inserted != 1) {
            throw new IllegalStateException("metadados da foto " + photoId + " ausentes ao gravar os bytes");
        }
    }

    @Override
    public Optional<byte[]> get(UUID userId, UUID goalId, UUID photoId, Variant variant) {
        String sql = variant == Variant.THUMBNAIL ? SELECT_THUMBNAIL : SELECT_DISPLAY;
        return jdbc.query(sql, (rs, row) -> rs.getBytes(1), photoId, userId, goalId).stream().findFirst();
    }

    @Override
    public void delete(UUID userId, UUID photoId) {
        jdbc.update("DELETE FROM goal_photo_blobs WHERE photo_id = ? AND user_id = ?", photoId, userId);
    }
}
