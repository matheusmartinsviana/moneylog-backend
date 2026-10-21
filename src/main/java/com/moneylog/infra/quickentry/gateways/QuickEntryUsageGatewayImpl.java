package com.moneylog.infra.quickentry.gateways;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.moneylog.core.quickentry.app.gateways.QuickEntryUsageGateway;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

public class QuickEntryUsageGatewayImpl implements QuickEntryUsageGateway {

    private static final String LOCK_NAMESPACE = "quick-entry:";

    private final JdbcTemplate jdbc;

    public QuickEntryUsageGatewayImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockUser(UUID userId) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))::text", String.class, LOCK_NAMESPACE + userId);
    }

    @Override
    public Usage activeUsage(UUID userId, Instant fromInclusive, Instant toExclusive) {
        return jdbc.queryForObject("""
                SELECT count(*) AS entries,
                       coalesce(sum(t.amount_cents) FILTER (WHERE t.type = 'EXPENSE'), 0) AS expense_cents,
                       coalesce(sum(t.amount_cents) FILTER (WHERE t.type = 'INCOME'), 0) AS income_cents
                FROM quick_entry_audit a
                JOIN transactions t ON t.id = a.transaction_id AND t.user_id = a.user_id
                WHERE a.user_id = ? AND a.created_at >= ? AND a.created_at < ? AND t.status = 'ACTIVE'""",
                (rs, row) -> new Usage(rs.getLong("entries"), Money.ofCents(rs.getLong("expense_cents"), CurrencyCode.BRL),
                        Money.ofCents(rs.getLong("income_cents"), CurrencyCode.BRL)),
                userId, Timestamp.from(fromInclusive), Timestamp.from(toExclusive));
    }
}
