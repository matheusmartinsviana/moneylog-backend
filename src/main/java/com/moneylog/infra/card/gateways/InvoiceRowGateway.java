package com.moneylog.infra.card.gateways;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.moneylog.core.card.app.gateways.InvoiceLookupGateway;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InvoiceIds;
import com.moneylog.core.card.domain.InvoicePeriod;
import com.moneylog.core.sk.time.TimeProvider;

public class InvoiceRowGateway implements InvoiceLookupGateway {

    public record InvoiceRow(UUID id, UUID cardId, LocalDate closingDate) {
    }

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;
    private final TimeProvider time;

    public InvoiceRowGateway(JdbcTemplate jdbc, TimeProvider time) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
        this.time = time;
    }

    public UUID findOrCreate(UUID userId, CreditCard card, LocalDate closingDate) {
        InvoicePeriod period = card.calendar().periodClosingIn(YearMonth.from(closingDate));
        if (!period.closingDate().equals(closingDate)) {
            throw new IllegalStateException("a data " + closingDate + " não é fechamento do calendário do cartão " + card.id());
        }
        Timestamp now = Timestamp.from(time.now());
        jdbc.update("""
                INSERT INTO credit_card_invoices (id, user_id, card_id, period_start, closing_date, due_date, reference_month,
                                                  created_at, updated_at, version)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                ON CONFLICT DO NOTHING""",
                InvoiceIds.of(card.id(), closingDate), userId, card.id(), Date.valueOf(period.periodStart()),
                Date.valueOf(closingDate), Date.valueOf(period.dueDate()), period.referenceMonth().toString(), now, now);
        return jdbc.queryForObject("SELECT id FROM credit_card_invoices WHERE user_id = ? AND card_id = ? AND closing_date = ?",
                UUID.class, userId, card.id(), Date.valueOf(closingDate));
    }

    public Map<UUID, InvoiceRow> rowsByIds(UUID userId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("uid", userId).addValue("ids", ids);
        return index(named.query("SELECT id, card_id, closing_date FROM credit_card_invoices WHERE user_id = :uid AND id IN (:ids)", params,
                (rs, row) -> toRow(rs)));
    }

    public Map<UUID, InvoiceRow> rowsOfUser(UUID userId) {
        return index(jdbc.query("SELECT id, card_id, closing_date FROM credit_card_invoices WHERE user_id = ?",
                (rs, row) -> toRow(rs), userId));
    }

    @Override
    public java.util.Optional<InvoiceKey> findById(UUID userId, UUID id) {
        return jdbc.query("SELECT id, card_id, closing_date FROM credit_card_invoices WHERE user_id = ? AND id = ?",
                (rs, row) -> toRow(rs), userId, id).stream().findFirst().map(row -> new InvoiceKey(row.cardId(), row.closingDate()));
    }

    private static InvoiceRow toRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new InvoiceRow(rs.getObject("id", UUID.class), rs.getObject("card_id", UUID.class),
                rs.getDate("closing_date").toLocalDate());
    }

    private static Map<UUID, InvoiceRow> index(List<InvoiceRow> rows) {
        Map<UUID, InvoiceRow> byId = new HashMap<>();
        rows.forEach(row -> byId.put(row.id(), row));
        return byId;
    }

    public void refresh(UUID userId, CreditCard card) {
        Timestamp now = Timestamp.from(time.now());
        List<InvoiceRow> rows = jdbc.query("SELECT id, card_id, closing_date FROM credit_card_invoices WHERE user_id = ? AND card_id = ?",
                (rs, row) -> toRow(rs), userId, card.id());
        for (InvoiceRow row : rows) {
            if (card.calendar().isClosingDate(row.closingDate())) {
                InvoicePeriod period = card.calendar().periodClosingIn(YearMonth.from(row.closingDate()));
                jdbc.update("""
                        UPDATE credit_card_invoices SET period_start = ?, due_date = ?, reference_month = ?, updated_at = ?
                        WHERE id = ? AND user_id = ? AND (period_start <> ? OR due_date <> ? OR reference_month <> ?)""",
                        Date.valueOf(period.periodStart()), Date.valueOf(period.dueDate()), period.referenceMonth().toString(), now,
                        row.id(), userId, Date.valueOf(period.periodStart()), Date.valueOf(period.dueDate()), period.referenceMonth().toString());
            } else if (referenced(userId, row.id())) {
                throw new IllegalStateException("a fatura " + row.id() + " (fechamento " + row.closingDate()
                        + ") saiu do calendário do cartão " + card.id() + " e ainda tem movimentação: cadeia inconsistente.");
            } else {
                jdbc.update("DELETE FROM credit_card_invoices WHERE id = ? AND user_id = ?", row.id(), userId);
            }
        }
    }

    private boolean referenced(UUID userId, UUID invoiceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM transaction_installments WHERE user_id = ? AND invoice_id = ?)
                    OR EXISTS (SELECT 1 FROM transactions WHERE user_id = ? AND invoice_id = ?)""",
                Boolean.class, userId, invoiceId, userId, invoiceId));
    }
}
