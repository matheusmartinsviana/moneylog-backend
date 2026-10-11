package com.moneylog.infra.insights.gateways;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.moneylog.core.insights.app.gateways.InsightDismissalGateway;
import com.moneylog.core.insights.domain.Dismissal;

public class InsightDismissalGatewayImpl implements InsightDismissalGateway {

    private final JdbcTemplate jdbc;

    public InsightDismissalGatewayImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Dismissal> findActive(UUID userId) {
        return jdbc.query("""
                SELECT id, user_id, fingerprint, type, subject_id, scope, dismissed_on, created_at, revoked_at
                FROM insight_dismissals WHERE user_id = ? AND revoked_at IS NULL ORDER BY created_at, id""",
                (rs, i) -> new Dismissal(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("fingerprint"),
                        rs.getString("type"), rs.getString("subject_id"), Dismissal.Scope.valueOf(rs.getString("scope")),
                        rs.getObject("dismissed_on", java.time.LocalDate.class), rs.getTimestamp("created_at").toInstant(), null),
                userId);
    }

    @Override
    public void insert(Dismissal d) {
        jdbc.update("""
                INSERT INTO insight_dismissals (id, user_id, fingerprint, type, subject_id, scope, dismissed_on, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                d.id(), d.userId(), d.fingerprint(), d.type(), d.subjectId(), d.scope().name(), d.dismissedOn(), Timestamp.from(d.createdAt()));
    }

    @Override
    public int revoke(UUID userId, Collection<UUID> ids, Instant at) {
        int changed = 0;
        for (UUID id : ids) {
            changed += jdbc.update("UPDATE insight_dismissals SET revoked_at = ? WHERE id = ? AND user_id = ? AND revoked_at IS NULL",
                    Timestamp.from(at), id, userId);
        }
        return changed;
    }
}
