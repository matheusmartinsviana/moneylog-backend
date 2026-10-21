package com.moneylog.infra.quickentry.gateways;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.moneylog.core.quickentry.app.gateways.QuickEntryUserLock;

public class QuickEntryUserLockImpl implements QuickEntryUserLock {

    private static final String NAMESPACE = "quick_entry_tokens:";

    private final JdbcTemplate jdbc;

    public QuickEntryUserLockImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean lock(UUID userId) {
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class, NAMESPACE + userId);
        return Boolean.TRUE.equals(jdbc.query("select status = 'ACTIVE' from users where id = ?", rs -> rs.next() ? rs.getBoolean(1) : null, userId));
    }
}
