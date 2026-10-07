package com.moneylog.infra.notification.gateways;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.moneylog.core.notification.app.gateways.PushSubscriptionGateway;

public class PushSubscriptionGatewayImpl implements PushSubscriptionGateway {

    private final JdbcTemplate jdbc;

    public PushSubscriptionGatewayImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PushSubscription upsert(PushSubscription s) {
        return jdbc.queryForObject("""
                INSERT INTO push_subscriptions (id, user_id, endpoint, p256dh, auth, user_agent, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, endpoint) DO UPDATE SET p256dh = EXCLUDED.p256dh, auth = EXCLUDED.auth, user_agent = EXCLUDED.user_agent
                RETURNING id, user_id, endpoint, p256dh, auth, user_agent, created_at""", (rs, i) -> map(rs), s.id(), s.userId(), s.endpoint(), s.p256dh(),
                s.auth(), s.userAgent(), Timestamp.from(s.createdAt()));
    }

    @Override
    public int count(UUID userId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM push_subscriptions WHERE user_id = ?", Integer.class, userId);
        return count == null ? 0 : count;
    }

    @Override
    public boolean delete(UUID userId, UUID id) {
        return jdbc.update("DELETE FROM push_subscriptions WHERE user_id = ? AND id = ?", userId, id) > 0;
    }

    @Override
    public void deleteByEndpoint(String endpoint) {
        jdbc.update("DELETE FROM push_subscriptions WHERE endpoint = ?", endpoint);
    }

    @Override
    public List<PushSubscription> listByUser(UUID userId) {
        return jdbc.query("SELECT id, user_id, endpoint, p256dh, auth, user_agent, created_at FROM push_subscriptions WHERE user_id = ? "
                + "ORDER BY created_at, id", (rs, i) -> map(rs), userId);
    }

    private static PushSubscription map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PushSubscription(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("endpoint"), rs.getString("p256dh"),
                rs.getString("auth"), rs.getString("user_agent"), rs.getTimestamp("created_at").toInstant());
    }
}
