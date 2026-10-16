package com.moneylog.infra.export;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.moneylog.core.finance.app.export.RestoreTargetGateway;

public class JdbcRestoreTargetGateway implements RestoreTargetGateway {

    private static final List<String> TABLES = List.of("accounts", "credit_cards", "persons", "transactions", "debts",
            "recurring_transactions", "goals", "assets", "income_profiles", "tracked_commitments", "purchase_groups",
            "third_party_expenses", "third_party_receipts", "categorization_rules", "import_batches");

    private final JdbcTemplate jdbc;

    public JdbcRestoreTargetGateway(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockUser(UUID userId) {
        jdbc.query("SELECT id FROM users WHERE id = ? FOR NO KEY UPDATE", (rs, i) -> rs.getObject(1), userId);
    }

    @Override
    public boolean hasData(UUID userId) {
        for (String table : TABLES) {
            if (exists("SELECT EXISTS (SELECT 1 FROM " + table + " WHERE user_id = ?)", userId)) {
                return true;
            }
        }
        return exists("SELECT EXISTS (SELECT 1 FROM categories WHERE user_id = ? AND NOT system)", userId);
    }

    private boolean exists(String sql, UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, userId));
    }
}
