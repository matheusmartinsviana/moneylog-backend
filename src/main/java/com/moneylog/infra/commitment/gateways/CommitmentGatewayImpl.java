package com.moneylog.infra.commitment.gateways;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.moneylog.core.commitment.app.gateways.CommitmentGateway;
import com.moneylog.core.commitment.domain.CommitmentKind;
import com.moneylog.core.commitment.domain.ScheduleItem;
import com.moneylog.core.commitment.domain.ScheduleItemType;
import com.moneylog.core.commitment.domain.TrackedCommitment;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;

/** Persistência por JDBC das tabelas {@code tracked_commitments} e {@code tracked_commitment_items} (V8.06). Sempre filtra por usuário. */
public class CommitmentGatewayImpl implements CommitmentGateway {

    private final JdbcTemplate jdbc;
    private final TimeProvider time;

    public CommitmentGatewayImpl(JdbcTemplate jdbc, TimeProvider time) {
        this.jdbc = jdbc;
        this.time = time;
    }

    @Override
    public Versioned<TrackedCommitment> insert(TrackedCommitment c) {
        Timestamp now = Timestamp.from(time.now());
        jdbc.update("""
                INSERT INTO tracked_commitments (id, user_id, kind, name, counterparty, notes, total_cents, informed_paid_cents,
                    installments_total, installments_paid, next_due_date, created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)""",
                c.id(), c.userId(), c.kind().name(), c.name(), c.counterparty(), c.notes(),
                c.declaredTotal() == null ? null : c.declaredTotal().cents(), c.informedPaid() == null ? null : c.informedPaid().cents(),
                c.installmentsTotal(), c.installmentsPaid(), toSql(c.storedNextDueDate()), now, now);
        writeItems(c);
        return findById(c.userId(), c.id()).orElseThrow();
    }

    @Override
    public Versioned<TrackedCommitment> update(TrackedCommitment c, long expectedVersion) {
        int rows = jdbc.update("""
                UPDATE tracked_commitments SET notes = ?, informed_paid_cents = ?, installments_paid = ?, next_due_date = ?,
                    updated_at = ?, version = version + 1
                WHERE id = ? AND user_id = ? AND version = ?""",
                c.notes(), c.informedPaid() == null ? null : c.informedPaid().cents(), c.installmentsPaid(),
                toSql(c.storedNextDueDate()), Timestamp.from(time.now()), c.id(), c.userId(), expectedVersion);
        if (rows == 0) {
            long stored = jdbc.query("SELECT version FROM tracked_commitments WHERE id = ? AND user_id = ?",
                    (rs, i) -> rs.getLong(1), c.id(), c.userId()).stream().findFirst().orElse(-1L);
            throw new VersionConflictException(expectedVersion, stored);
        }
        jdbc.update("DELETE FROM tracked_commitment_items WHERE commitment_id = ? AND user_id = ?", c.id(), c.userId());
        writeItems(c);
        return findById(c.userId(), c.id()).orElseThrow();
    }

    private void writeItems(TrackedCommitment c) {
        for (ScheduleItem i : c.schedule()) {
            jdbc.update("""
                    INSERT INTO tracked_commitment_items (commitment_id, user_id, position, label, item_type, due_date, amount_cents, paid, paid_on)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    c.id(), c.userId(), i.position(), i.label(), i.type().name(), toSql(i.dueDate()), i.amount().cents(), i.isPaid(), toSql(i.paidOn()));
        }
    }

    @Override
    public void lockForUpdate(UUID userId, UUID id) {
        jdbc.query("SELECT id FROM tracked_commitments WHERE id = ? AND user_id = ? FOR UPDATE", (rs, i) -> rs.getObject(1), id, userId);
    }

    @Override
    public Optional<Versioned<TrackedCommitment>> findById(UUID userId, UUID id) {
        return jdbc.query("SELECT * FROM tracked_commitments WHERE id = ? AND user_id = ?", (rs, i) -> map(rs), id, userId).stream().findFirst();
    }

    @Override
    public Slice<Versioned<TrackedCommitment>> list(UUID userId, PageQuery page) {
        if (page.isBeyondAnyData()) {
            return Slice.empty();
        }
        List<Versioned<TrackedCommitment>> fetched = jdbc.query(
                "SELECT * FROM tracked_commitments WHERE user_id = ? ORDER BY name ASC, id ASC LIMIT ? OFFSET ?",
                (rs, i) -> map(rs), userId, page.fetchSize(), page.offset());
        return Slice.fromOverfetch(fetched, page);
    }

    @Override
    public List<TrackedCommitment> findAll(UUID userId) {
        return jdbc.query("SELECT * FROM tracked_commitments WHERE user_id = ? ORDER BY name ASC, id ASC", (rs, i) -> map(rs).value(), userId);
    }

    private Versioned<TrackedCommitment> map(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        UUID userId = rs.getObject("user_id", UUID.class);
        List<ScheduleItem> items = new ArrayList<>(jdbc.query("""
                SELECT position, label, item_type, due_date, amount_cents, paid, paid_on FROM tracked_commitment_items
                WHERE commitment_id = ? AND user_id = ? ORDER BY position""", (r, n) -> new ScheduleItem(r.getInt("position"),
                r.getString("label"), ScheduleItemType.valueOf(r.getString("item_type")), toLocal(r.getDate("due_date")),
                Money.ofCents(r.getLong("amount_cents"), CurrencyCode.BRL), r.getBoolean("paid"), toLocal(r.getDate("paid_on"))), id, userId));
        TrackedCommitment commitment = TrackedCommitment.restore(id, userId, CommitmentKind.valueOf(rs.getString("kind")),
                rs.getString("name"), rs.getString("counterparty"), rs.getString("notes"), money(rs, "total_cents"),
                money(rs, "informed_paid_cents"), (Integer) rs.getObject("installments_total"), (Integer) rs.getObject("installments_paid"),
                toLocal(rs.getDate("next_due_date")), items);
        return new Versioned<>(commitment, rs.getLong("version"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static Money money(ResultSet rs, String column) throws SQLException {
        long cents = rs.getLong(column);
        return rs.wasNull() ? null : Money.ofCents(cents, CurrencyCode.BRL);
    }

    private static Date toSql(LocalDate d) {
        return d == null ? null : Date.valueOf(d);
    }

    private static LocalDate toLocal(Date d) {
        return d == null ? null : d.toLocalDate();
    }
}
