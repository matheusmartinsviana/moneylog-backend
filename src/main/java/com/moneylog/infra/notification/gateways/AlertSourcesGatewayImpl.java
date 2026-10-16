package com.moneylog.infra.notification.gateways;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.moneylog.core.notification.app.gateways.AlertSourcesGateway;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

public class AlertSourcesGatewayImpl implements AlertSourcesGateway {

    private final JdbcTemplate jdbc;

    public AlertSourcesGatewayImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Created createdBetween(UUID userId, Instant from, Instant to) {
        Timestamp start = Timestamp.from(from);
        Timestamp end = Timestamp.from(to);
        List<NewPurchase> purchases = jdbc.query("""
                SELECT t.id, t.amount_cents, t.installments FROM transactions t
                WHERE t.user_id = ? AND t.type = 'CARD_PURCHASE' AND t.status = 'ACTIVE' AND t.created_at >= ? AND t.created_at < ?
                  AND (t.installments > 1 OR EXISTS (SELECT 1 FROM debts d WHERE d.origin_transaction_id = t.id AND d.kind = 'CARD_CASH_OUT'))
                ORDER BY t.created_at, t.id""",
                (rs, i) -> new NewPurchase(rs.getObject("id", UUID.class), Money.ofCents(rs.getLong("amount_cents"), CurrencyCode.BRL),
                        rs.getInt("installments")), userId, start, end);
        List<NewCashOut> cashOuts = jdbc.query("""
                SELECT d.id, d.origin_transaction_id FROM debts d
                WHERE d.user_id = ? AND d.kind = 'CARD_CASH_OUT' AND d.closure IS NULL AND d.origin_transaction_id IS NOT NULL
                  AND d.created_at >= ? AND d.created_at < ?
                ORDER BY d.created_at, d.id""",
                (rs, i) -> new NewCashOut(rs.getObject("id", UUID.class), rs.getObject("origin_transaction_id", UUID.class)), userId, start, end);
        List<UUID> recurrences = jdbc.query("SELECT id FROM recurring_transactions WHERE user_id = ? AND created_at >= ? AND created_at < ?",
                (rs, i) -> rs.getObject("id", UUID.class), userId, start, end);
        return new Created(purchases, cashOuts, new HashSet<>(recurrences));
    }
}
