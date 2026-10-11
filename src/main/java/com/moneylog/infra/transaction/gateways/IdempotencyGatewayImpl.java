package com.moneylog.infra.transaction.gateways;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;

public class IdempotencyGatewayImpl implements IdempotencyGateway {

    private final JdbcTemplate jdbc;

    public IdempotencyGatewayImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean claim(UUID userId, String scope, UUID key, String requestHash, UUID resourceId, Instant now, Instant expiredBefore) {
        int changed = jdbc.update("""
                INSERT INTO idempotency_keys (id, user_id, scope, idempotency_key, request_hash, resource_id, created_at, updated_at)
                VALUES (gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT ON CONSTRAINT uq_idempotency_keys_user_scope_key DO UPDATE
                    SET request_hash = EXCLUDED.request_hash, resource_id = EXCLUDED.resource_id, created_at = EXCLUDED.created_at, updated_at = EXCLUDED.updated_at
                    WHERE idempotency_keys.created_at <= ?""",
                userId, scope, key, requestHash, resourceId, Timestamp.from(now), Timestamp.from(now), Timestamp.from(expiredBefore));
        return changed == 1;
    }

    @Override
    public Optional<Claim> find(UUID userId, String scope, UUID key) {
        return jdbc.query("""
                SELECT request_hash, resource_id, created_at FROM idempotency_keys
                WHERE user_id = ? AND scope = ? AND idempotency_key = ?""",
                (rs, row) -> new Claim(rs.getString("request_hash").strip(), rs.getObject("resource_id", UUID.class),
                        rs.getTimestamp("created_at").toInstant()),
                userId, scope, key).stream().findFirst();
    }
}
