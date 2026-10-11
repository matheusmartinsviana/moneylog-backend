package com.moneylog.infra.search.gateways;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.moneylog.core.account.domain.AccountStatus;
import com.moneylog.core.account.domain.AccountType;
import com.moneylog.core.card.domain.CardStatus;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.note.domain.NoteSnippets;
import com.moneylog.core.search.app.gateways.SearchGateway;
import com.moneylog.core.search.domain.SearchGroup;
import com.moneylog.core.search.domain.SearchResults;
import com.moneylog.core.search.domain.SearchResults.AccountHit;
import com.moneylog.core.search.domain.SearchResults.CardHit;
import com.moneylog.core.search.domain.SearchResults.CategoryHit;
import com.moneylog.core.search.domain.SearchResults.NoteHit;
import com.moneylog.core.search.domain.SearchResults.Ref;
import com.moneylog.core.search.domain.SearchResults.TransactionHit;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.TransactionType;

public class SearchGatewayImpl implements SearchGateway {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(3);
    private static final String QUERY_CANCELED = "57014";
    private static final int SNIPPET_MAX = 160;
    private static final int BODY_HEAD = 400;

    static final String PATTERN_SQL = "('%' || replace(replace(replace(unaccent(normalize(?, NFC)), '\\', '\\\\'), '%', '\\%'), '_', '\\_') || '%')";

    private final JdbcTemplate jdbc;
    private final long timeoutMillis;

    public SearchGatewayImpl(JdbcTemplate jdbc, Duration timeout) {
        this.jdbc = jdbc;
        this.timeoutMillis = Math.max(1, timeout.toMillis());
    }

    public static final class SearchFailedException extends RuntimeException {
        public SearchFailedException(String sqlState) {
            super("falha ao executar a busca (SQLState " + sqlState + ")");
        }
    }

    @FunctionalInterface
    interface Query<T> {
        List<T> run(Connection connection) throws SQLException;
    }

    private record Outcome<T>(List<T> rows, boolean truncated) {
    }

    @Override
    public SearchResults search(UUID userId, String q, int limit) {
        String pattern = q;
        return jdbc.execute((Connection connection) -> {
            Set<SearchGroup> truncated = EnumSet.noneOf(SearchGroup.class);
            List<TransactionHit> transactions = group(connection, SearchGroup.TRANSACTIONS, truncated, c -> transactions(c, userId, pattern, limit));
            List<AccountHit> accounts = group(connection, SearchGroup.ACCOUNTS, truncated, c -> accounts(c, userId, pattern, limit));
            List<CategoryHit> categories = group(connection, SearchGroup.CATEGORIES, truncated, c -> categories(c, userId, pattern, limit));
            List<CardHit> cards = group(connection, SearchGroup.CARDS, truncated, c -> cards(c, userId, pattern, limit));
            List<NoteHit> notes = group(connection, SearchGroup.NOTES, truncated, c -> notes(c, userId, pattern, limit));
            return new SearchResults(q, transactions, accounts, categories, cards, notes, truncated);
        });
    }

    <T> List<T> group(Connection connection, SearchGroup group, Set<SearchGroup> truncated, Query<T> query) throws SQLException {
        Outcome<T> outcome = guarded(connection, query);
        if (outcome.truncated()) {
            truncated.add(group);
        }
        return outcome.rows();
    }

    private <T> Outcome<T> guarded(Connection connection, Query<T> query) throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        try {
            try (PreparedStatement timeout = connection.prepareStatement("SELECT set_config('statement_timeout', ?, true)")) {
                timeout.setString(1, Long.toString(timeoutMillis));
                timeout.execute();
            }
            List<T> rows = query.run(connection);
            connection.rollback(savepoint);
            return new Outcome<>(rows, false);
        } catch (SQLException e) {
            if (QUERY_CANCELED.equals(e.getSQLState())) {
                connection.rollback(savepoint);
                return new Outcome<>(List.of(), true);
            }
            throw new SearchFailedException(e.getSQLState());
        }
    }

    private static List<TransactionHit> transactions(Connection c, UUID userId, String pattern, int limit) throws SQLException {
        String sql = """
                SELECT t.id, t.description, t.transaction_date, t.amount_cents, t.type, t.category_id, cat.name AS category_name,
                       t.account_id, acc.name AS account_name, t.income_competence
                FROM transactions t
                CROSS JOIN (SELECT {PAT} AS pat) p
                LEFT JOIN categories cat ON cat.id = t.category_id AND cat.user_id = t.user_id
                LEFT JOIN accounts acc ON acc.id = t.account_id AND acc.user_id = t.user_id
                WHERE t.user_id = ? AND t.status = 'ACTIVE' AND t.description IS NOT NULL AND unaccent(normalize(t.description, NFC)) ILIKE p.pat
                ORDER BY t.transaction_date DESC, t.created_at DESC, t.id DESC
                LIMIT ?""".replace("{PAT}", PATTERN_SQL);
        return query(c, sql, ps -> {
            ps.setString(1, pattern);
            ps.setObject(2, userId);
            ps.setInt(3, limit);
        }, rs -> new TransactionHit(rs.getObject("id", UUID.class), rs.getString("description"),
                rs.getObject("transaction_date", java.time.LocalDate.class), Money.ofCents(rs.getLong("amount_cents"), CurrencyCode.BRL),
                TransactionType.valueOf(rs.getString("type")), ref(rs, "category_id", "category_name"), ref(rs, "account_id", "account_name"),
                rs.getString("income_competence") == null ? null : rs.getString("income_competence").strip()));
    }

    private static List<AccountHit> accounts(Connection c, UUID userId, String pattern, int limit) throws SQLException {
        String sql = """
                SELECT a.id, a.name, a.institution, a.type, a.status FROM accounts a
                WHERE a.user_id = ? AND (unaccent(normalize(a.name, NFC)) ILIKE {PAT} OR (a.institution IS NOT NULL AND unaccent(normalize(a.institution, NFC)) ILIKE {PAT}))
                ORDER BY (a.status = 'ARCHIVED'), unaccent(lower(a.name)), a.id
                LIMIT ?""".replace("{PAT}", PATTERN_SQL);
        return query(c, sql, ps -> {
            ps.setObject(1, userId);
            ps.setString(2, pattern);
            ps.setString(3, pattern);
            ps.setInt(4, limit);
        }, rs -> new AccountHit(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("institution"),
                AccountType.valueOf(rs.getString("type")), AccountStatus.valueOf(rs.getString("status"))));
    }

    private static List<CategoryHit> categories(Connection c, UUID userId, String pattern, int limit) throws SQLException {
        String sql = """
                SELECT c.id, c.name, c.kind, c.archived FROM categories c
                WHERE c.user_id = ? AND unaccent(normalize(c.name, NFC)) ILIKE {PAT}
                ORDER BY c.archived, unaccent(lower(c.name)), c.id
                LIMIT ?""".replace("{PAT}", PATTERN_SQL);
        return query(c, sql, userId, pattern, limit, rs -> new CategoryHit(rs.getObject("id", UUID.class), rs.getString("name"),
                CategoryKind.valueOf(rs.getString("kind")), rs.getBoolean("archived")));
    }

    private static List<CardHit> cards(Connection c, UUID userId, String pattern, int limit) throws SQLException {
        String sql = """
                SELECT k.id, k.name, k.institution, k.status FROM credit_cards k
                WHERE k.user_id = ? AND (unaccent(normalize(k.name, NFC)) ILIKE {PAT} OR (k.institution IS NOT NULL AND unaccent(normalize(k.institution, NFC)) ILIKE {PAT}))
                ORDER BY (k.status = 'ARCHIVED'), unaccent(lower(k.name)), k.id
                LIMIT ?""".replace("{PAT}", PATTERN_SQL);
        return query(c, sql, ps -> {
            ps.setObject(1, userId);
            ps.setString(2, pattern);
            ps.setString(3, pattern);
            ps.setInt(4, limit);
        }, rs -> new CardHit(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("institution"),
                CardStatus.valueOf(rs.getString("status"))));
    }

    private static List<NoteHit> notes(Connection c, UUID userId, String pattern, int limit) throws SQLException {
        String sql = """
                SELECT n.id, n.title, n.path, substr(n.body, 1, %d) AS head FROM notes n
                WHERE n.user_id = ? AND n.deleted_at IS NULL AND (unaccent(normalize(n.title, NFC)) ILIKE {PAT} OR unaccent(normalize(n.path, NFC)) ILIKE {PAT})
                ORDER BY n.updated_at DESC, n.id
                LIMIT ?""".formatted(BODY_HEAD).replace("{PAT}", PATTERN_SQL);
        return query(c, sql, ps -> {
            ps.setObject(1, userId);
            ps.setString(2, pattern);
            ps.setString(3, pattern);
            ps.setInt(4, limit);
        }, rs -> new NoteHit(rs.getObject("id", UUID.class), rs.getString("title"), rs.getString("path"), snippet(rs.getString("head"))));
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    @FunctionalInterface
    private interface RowMapper<T> {
        T map(ResultSet resultSet) throws SQLException;
    }

    private static <T> List<T> query(Connection c, String sql, UUID userId, String pattern, int limit, RowMapper<T> mapper) throws SQLException {
        return query(c, sql, ps -> {
            ps.setObject(1, userId);
            ps.setString(2, pattern);
            ps.setInt(3, limit);
        }, mapper);
    }

    private static <T> List<T> query(Connection c, String sql, Binder binder, RowMapper<T> mapper) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapper.map(rs));
                }
                return rows;
            }
        }
    }

    private static Ref ref(ResultSet rs, String idColumn, String nameColumn) throws SQLException {
        UUID id = rs.getObject(idColumn, UUID.class);
        return id == null ? null : new Ref(id, rs.getString(nameColumn));
    }

    static String snippet(String head) {
        if (head == null) {
            return "";
        }
        return NoteSnippets.head(head.replaceAll("\\p{Cc}", " "), SNIPPET_MAX);
    }

    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

}
