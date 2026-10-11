package com.moneylog.infra.importing.gateways;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.moneylog.core.importing.app.gateways.ImportHistoryGateway;
import com.moneylog.core.importing.domain.CategorySuggester.Rule;
import com.moneylog.core.importing.domain.ImportDirection;
import com.moneylog.core.importing.parser.ColumnMapping;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.time.TimeProvider;

public class ImportHistoryGatewayImpl implements ImportHistoryGateway {

    private final JdbcTemplate jdbc;
    private final TimeProvider time;

    public ImportHistoryGatewayImpl(JdbcTemplate jdbc, TimeProvider time) {
        this.jdbc = jdbc;
        this.time = time;
    }

    @Override
    public List<HistoryRow> recentCategorized(UUID userId, int limit) {
        return jdbc.query("""
                SELECT t.description, t.category_id, t.payment_method,
                       CASE WHEN t.type IN ('INCOME', 'REFUND') THEN 'IN' ELSE 'OUT' END AS dir
                FROM transactions t
                WHERE t.user_id = ? AND t.status = 'ACTIVE' AND t.category_id IS NOT NULL AND t.description IS NOT NULL
                  AND t.type IN ('EXPENSE', 'INCOME', 'CARD_PURCHASE')
                ORDER BY t.transaction_date DESC, t.id LIMIT ?""", (rs, n) -> new HistoryRow(ImportDirection.valueOf(rs.getString("dir")),
                rs.getString("description"), rs.getObject("category_id", UUID.class), rs.getString("payment_method")), userId, limit);
    }

    @Override
    public List<Rule> rules(UUID userId) {
        return jdbc.query("SELECT direction, normalized_description, category_id, method FROM categorization_rules WHERE user_id = ?",
                (rs, n) -> new Rule(ImportDirection.valueOf(rs.getString("direction")), rs.getString("normalized_description"),
                        rs.getObject("category_id", UUID.class), rs.getString("method")), userId);
    }

    @Override
    public Slice<StoredRule> listRules(UUID userId, PageQuery page) {
        if (page.isBeyondAnyData()) {
            return Slice.empty();
        }
        List<StoredRule> rows = jdbc.query("SELECT id, direction, normalized_description, category_id, method FROM categorization_rules "
                        + "WHERE user_id = ? ORDER BY normalized_description, id LIMIT ? OFFSET ?",
                (rs, n) -> new StoredRule(rs.getObject("id", UUID.class), ImportDirection.valueOf(rs.getString("direction")),
                        rs.getString("normalized_description"), rs.getObject("category_id", UUID.class), rs.getString("method")),
                userId, page.fetchSize(), page.offset());
        return Slice.fromOverfetch(rows, page);
    }

    @Override
    public void upsertRule(UUID userId, UUID id, ImportDirection direction, String normalizedDescription, UUID categoryId, String method) {
        jdbc.update("""
                INSERT INTO categorization_rules (id, user_id, direction, normalized_description, category_id, method, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, direction, normalized_description) DO UPDATE SET category_id = EXCLUDED.category_id, method = EXCLUDED.method""",
                id, userId, direction.name(), normalizedDescription, categoryId, method, Timestamp.from(time.now()));
    }

    @Override
    public boolean deleteRule(UUID userId, UUID ruleId) {
        return jdbc.update("DELETE FROM categorization_rules WHERE user_id = ? AND id = ?", userId, ruleId) > 0;
    }

    @Override
    public Optional<ColumnMapping> findMapping(UUID userId, UUID sourceId) {
        return jdbc.query("SELECT date_col, description_col, amount_col, debit_col, credit_col, invert, has_header, delimiter "
                        + "FROM import_mappings WHERE user_id = ? AND source_id = ?",
                (rs, n) -> new ColumnMapping(rs.getInt("date_col"), rs.getInt("description_col"), (Integer) rs.getObject("amount_col"),
                        (Integer) rs.getObject("debit_col"), (Integer) rs.getObject("credit_col"), rs.getBoolean("invert"),
                        rs.getBoolean("has_header"), rs.getString("delimiter") == null ? null : rs.getString("delimiter").charAt(0)),
                userId, sourceId).stream().findFirst();
    }

    @Override
    public void saveMapping(UUID userId, UUID sourceId, ColumnMapping m) {
        jdbc.update("""
                INSERT INTO import_mappings (user_id, source_id, date_col, description_col, amount_col, debit_col, credit_col, invert, has_header,
                    delimiter, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, source_id) DO UPDATE SET date_col = EXCLUDED.date_col, description_col = EXCLUDED.description_col,
                    amount_col = EXCLUDED.amount_col, debit_col = EXCLUDED.debit_col, credit_col = EXCLUDED.credit_col, invert = EXCLUDED.invert,
                    has_header = EXCLUDED.has_header, delimiter = EXCLUDED.delimiter, updated_at = EXCLUDED.updated_at""",
                userId, sourceId, m.date(), m.description(), m.amount(), m.debit(), m.credit(), m.invert(), m.hasHeader(),
                m.delimiter() == null ? null : String.valueOf(m.delimiter()), Timestamp.from(time.now()));
    }
}
