package com.moneylog.infra.importing.gateways;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.moneylog.core.importing.app.gateways.ImportBatchGateway;
import com.moneylog.core.importing.domain.CategorySuggester.Confidence;
import com.moneylog.core.importing.domain.CategorySuggester.Suggestion;
import com.moneylog.core.importing.domain.DedupKey;
import com.moneylog.core.importing.domain.ImportBatch;
import com.moneylog.core.importing.domain.ImportBatchLine;
import com.moneylog.core.importing.domain.ImportBatchStatus;
import com.moneylog.core.importing.domain.ImportDirection;
import com.moneylog.core.importing.domain.ImportLine;
import com.moneylog.core.importing.domain.ImportLineFlag;
import com.moneylog.core.importing.domain.ImportLineReview;
import com.moneylog.core.importing.domain.ImportLineStatus;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;

/** Lotes e linhas de importação em JDBC; toda consulta filtra por {@code user_id}. O arquivo bruto não existe aqui. */
public class ImportBatchGatewayImpl implements ImportBatchGateway {

    private static final String BATCH_COLUMNS = "id, user_id, account_id, card_id, format, file_sha256, status, warnings, skipped_info, "
            + "applied_at, undone_at, undo_reason, created_at";
    private static final String LINE_COLUMNS = "id, batch_id, position, source_id, line_date, amount_cents, direction, description, fit_id, "
            + "dedup_key, effective_dedup_key, forced, status, flags, matches, reject_reason, category_id, method, description_override, "
            + "counterpart_account_id, learn, suggestion_category_id, suggestion_method, suggestion_confidence, transaction_id";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public ImportBatchGatewayImpl(JdbcTemplate jdbc, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tm);
    }

    // ------------------------------------------------------------------ lote

    @Override
    public void insert(ImportBatch b, List<ImportBatchLine> lines) {
        Timestamp now = Timestamp.from(b.createdAt());
        jdbc.update("""
                INSERT INTO import_batches (id, user_id, account_id, card_id, format, file_sha256, status, warnings, skipped_info,
                    applied_at, undone_at, undo_reason, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, NULL, ?, ?)""", b.id(), b.userId(), b.accountId(), b.cardId(), b.format(),
                b.fileSha256(), b.status().name(), String.join(",", b.warnings()), String.join("\n", b.skipped()), now, now);
        jdbc.batchUpdate("""
                INSERT INTO import_lines (id, user_id, batch_id, position, source_id, line_date, amount_cents, direction, description,
                    normalized_description, fit_id, dedup_key, effective_dedup_key, forced, status, flags, matches, reject_reason, category_id,
                    method, description_override, counterpart_account_id, learn, suggestion_category_id, suggestion_method,
                    suggestion_confidence, transaction_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", lines, 500, (ps, l) -> {
            ImportLine line = l.line();
            int i = 1;
            ps.setObject(i++, l.id());
            ps.setObject(i++, b.userId());
            ps.setObject(i++, b.id());
            ps.setInt(i++, l.position());
            ps.setObject(i++, line.sourceId());
            ps.setObject(i++, line.date());
            ps.setLong(i++, line.amount().cents());
            ps.setString(i++, line.direction().name());
            ps.setString(i++, nz(line.description()));
            ps.setString(i++, line.normalizedDescription());
            ps.setString(i++, line.fitId());
            ps.setString(i++, l.review().key().value());
            ps.setString(i++, l.review().effectiveKey().value());
            ps.setBoolean(i++, l.review().isForced());
            ps.setString(i++, l.status().name());
            ps.setString(i++, flagsOf(l));
            ps.setString(i++, l.review().matches().stream().map(UUID::toString).collect(Collectors.joining(",")));
            ps.setString(i++, l.rejectReason());
            ps.setObject(i++, l.categoryId());
            ps.setString(i++, l.method());
            ps.setString(i++, l.descriptionOverride());
            ps.setObject(i++, l.counterpartAccountId());
            ps.setBoolean(i++, l.learn());
            ps.setObject(i++, l.suggestion() == null ? null : l.suggestion().categoryId());
            ps.setString(i++, l.suggestion() == null ? null : l.suggestion().method());
            ps.setString(i++, l.suggestion() == null ? null : l.suggestion().confidence().name());
            ps.setObject(i, l.transactionId());
        });
    }

    @Override
    public Optional<ImportBatch> find(UUID userId, UUID batchId) {
        return jdbc.query("SELECT " + BATCH_COLUMNS + " FROM import_batches WHERE user_id = ? AND id = ?", (rs, n) -> batch(rs), userId, batchId)
                .stream().findFirst();
    }

    @Override
    public Optional<ImportBatch> findForUpdate(UUID userId, UUID batchId) {
        return jdbc.query("SELECT " + BATCH_COLUMNS + " FROM import_batches WHERE user_id = ? AND id = ? FOR UPDATE", (rs, n) -> batch(rs), userId,
                batchId).stream().findFirst();
    }

    @Override
    public void update(ImportBatch b) {
        // REQUIRED: junta-se à transação do aplicar; no desfazer (que não tem transação própria) abre a sua, senão o commit nunca acontece
        tx.executeWithoutResult(status -> jdbc.update("UPDATE import_batches SET status = ?, applied_at = ?, undone_at = ?, undo_reason = ?, updated_at = now() "
                        + "WHERE user_id = ? AND id = ?", b.status().name(), ts(b.appliedAt()), ts(b.undoneAt()), b.undoReason(), b.userId(), b.id()));
    }

    @Override
    public void delete(UUID userId, UUID batchId) {
        jdbc.update("DELETE FROM import_batches WHERE user_id = ? AND id = ? AND status = 'PENDING_REVIEW'", userId, batchId);
    }

    @Override
    public Slice<ImportBatch> list(UUID userId, ImportBatchStatus status, PageQuery page) {
        if (page.isBeyondAnyData()) {
            return Slice.empty();
        }
        List<ImportBatch> rows = jdbc.query("SELECT " + BATCH_COLUMNS + " FROM import_batches WHERE user_id = ? AND (?::text IS NULL OR status = ?) "
                        + "ORDER BY created_at DESC, id LIMIT ? OFFSET ?", (rs, n) -> batch(rs), userId, status == null ? null : status.name(),
                status == null ? null : status.name(), page.fetchSize(), page.offset());
        return Slice.fromOverfetch(rows, page);
    }

    @Override
    public Counts counts(UUID userId, UUID batchId) {
        return jdbc.queryForObject("""
                SELECT count(*) AS total,
                       count(*) FILTER (WHERE status = 'PENDING') AS pending,
                       count(*) FILTER (WHERE status = 'ACCEPTED') AS accepted,
                       count(*) FILTER (WHERE status = 'REJECTED') AS rejected,
                       count(*) FILTER (WHERE status = 'DUPLICATE') AS duplicate,
                       count(*) FILTER (WHERE status = 'POSSIBLE_DUPLICATE') AS possible_duplicate,
                       count(*) FILTER (WHERE status = 'POSSIBLE_DUPLICATE'
                           OR (status = 'PENDING' AND (flags LIKE '%LOOKS_LIKE_INSTALLMENT%' OR flags LIKE '%POSSIBLE_TRANSFER%'))) AS require_decision
                FROM import_lines WHERE user_id = ? AND batch_id = ?""",
                (rs, n) -> new Counts(rs.getInt("total"), rs.getInt("pending"), rs.getInt("accepted"), rs.getInt("rejected"),
                        rs.getInt("duplicate"), rs.getInt("possible_duplicate"), rs.getInt("require_decision")), userId, batchId);
    }

    @Override
    public Optional<UUID> findSameFile(UUID userId, UUID sourceId, String fileSha256) {
        return jdbc.query("SELECT id FROM import_batches WHERE user_id = ? AND file_sha256 = ? AND (account_id = ? OR card_id = ?) "
                        + "ORDER BY created_at DESC LIMIT 1", (rs, n) -> rs.getObject("id", UUID.class), userId, fileSha256, sourceId, sourceId)
                .stream().findFirst();
    }

    // ------------------------------------------------------------------ linhas

    @Override
    public Optional<ImportBatchLine> findLine(UUID userId, UUID batchId, UUID lineId) {
        return jdbc.query("SELECT " + LINE_COLUMNS + " FROM import_lines WHERE user_id = ? AND batch_id = ? AND id = ?", (rs, n) -> line(rs),
                userId, batchId, lineId).stream().findFirst();
    }

    @Override
    public void updateLine(UUID userId, ImportBatchLine l) {
        jdbc.update("""
                UPDATE import_lines SET status = ?, effective_dedup_key = ?, forced = ?, reject_reason = ?, category_id = ?, method = ?,
                    description_override = ?, counterpart_account_id = ?, learn = ?, transaction_id = ?
                WHERE user_id = ? AND id = ?""", l.status().name(), l.review().effectiveKey().value(), l.review().isForced(), l.rejectReason(),
                l.categoryId(), l.method(), l.descriptionOverride(), l.counterpartAccountId(), l.learn(), l.transactionId(), userId, l.id());
    }

    @Override
    public Slice<ImportBatchLine> listLines(UUID userId, UUID batchId, ImportLineStatus status, ImportLineFlag flag, PageQuery page) {
        if (page.isBeyondAnyData()) {
            return Slice.empty();
        }
        List<ImportBatchLine> rows = jdbc.query("SELECT " + LINE_COLUMNS + " FROM import_lines WHERE user_id = ? AND batch_id = ? "
                        + "AND (?::text IS NULL OR status = ?) AND (?::text IS NULL OR flags LIKE ?) ORDER BY position LIMIT ? OFFSET ?",
                (rs, n) -> line(rs), userId, batchId, status == null ? null : status.name(), status == null ? null : status.name(),
                flag == null ? null : flag.name(), flag == null ? null : "%" + flag.name() + "%", page.fetchSize(), page.offset());
        return Slice.fromOverfetch(rows, page);
    }

    @Override
    public List<ImportBatchLine> allLines(UUID userId, UUID batchId) {
        return jdbc.query("SELECT " + LINE_COLUMNS + " FROM import_lines WHERE user_id = ? AND batch_id = ? ORDER BY position", (rs, n) -> line(rs),
                userId, batchId);
    }

    @Override
    public List<UUID> activeTransactionIds(UUID userId, UUID batchId) {
        return jdbc.query("SELECT id FROM transactions WHERE user_id = ? AND import_batch_id = ? AND status = 'ACTIVE' ORDER BY created_at, id",
                (rs, n) -> rs.getObject("id", UUID.class), userId, batchId);
    }

    // ------------------------------------------------------------------ mapeamento

    private static ImportBatch batch(ResultSet rs) throws SQLException {
        return ImportBatch.restore(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getObject("account_id", UUID.class),
                rs.getObject("card_id", UUID.class), rs.getString("format"), rs.getString("file_sha256"), rs.getTimestamp("created_at").toInstant(),
                split(rs.getString("warnings"), ","), split(rs.getString("skipped_info"), "\n"), ImportBatchStatus.valueOf(rs.getString("status")),
                instant(rs.getTimestamp("applied_at")), instant(rs.getTimestamp("undone_at")), rs.getString("undo_reason"));
    }

    private static ImportBatchLine line(ResultSet rs) throws SQLException {
        ImportLine line = new ImportLine(rs.getObject("source_id", UUID.class), rs.getObject("line_date", java.time.LocalDate.class),
                Money.ofCents(rs.getLong("amount_cents"), CurrencyCode.BRL), ImportDirection.valueOf(rs.getString("direction")),
                rs.getString("description"), rs.getString("fit_id"));
        List<UUID> matches = split(rs.getString("matches"), ",").stream().map(UUID::fromString).toList();
        ImportLineReview review = ImportLineReview.restore(line, new DedupKey(rs.getString("dedup_key").strip()),
                new DedupKey(rs.getString("effective_dedup_key").strip()), ImportLineStatus.valueOf(rs.getString("status")), matches,
                rs.getBoolean("forced"));
        UUID suggestedCategory = rs.getObject("suggestion_category_id", UUID.class);
        Suggestion suggestion = suggestedCategory == null ? null : new Suggestion(suggestedCategory, rs.getString("suggestion_method"),
                Confidence.valueOf(rs.getString("suggestion_confidence")));
        List<ImportLineFlag> flags = split(rs.getString("flags"), ",").stream().map(ImportLineFlag::valueOf).toList();
        return ImportBatchLine.restore(rs.getObject("id", UUID.class), rs.getObject("batch_id", UUID.class), rs.getInt("position"), review, flags,
                suggestion, rs.getString("reject_reason"), rs.getObject("category_id", UUID.class), rs.getString("method"),
                rs.getString("description_override"), rs.getObject("counterpart_account_id", UUID.class), rs.getBoolean("learn"),
                rs.getObject("transaction_id", UUID.class));
    }

    private static String flagsOf(ImportBatchLine line) {
        return line.flags().stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    private static List<String> split(String text, String separator) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return new ArrayList<>(Arrays.stream(text.split(java.util.regex.Pattern.quote(separator))).filter(s -> !s.isBlank()).toList());
    }

    private static String nz(String text) {
        return text == null ? "" : text.length() > 500 ? text.substring(0, 500) : text;
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
