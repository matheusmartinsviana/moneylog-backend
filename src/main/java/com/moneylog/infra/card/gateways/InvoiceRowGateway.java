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

import com.moneylog.core.card.app.gateways.InvoiceLookupGateway;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InvoiceIds;
import com.moneylog.core.card.domain.InvoicePeriod;
import com.moneylog.core.sk.time.TimeProvider;

/**
 * As linhas de {@code credit_card_invoices} (ADR-006). A fatura é identificada por {@code (cartão, fechamento)} e a
 * linha só existe depois que recebe um item ou um pagamento; o id é o determinístico de {@link InvoiceIds}. Só a infra
 * conhece {@code invoice_id}: o domínio fala em {@code closingDate}.
 *
 * <p>SQL direto (JDBC), na transação do chamador: {@link #findOrCreate} é um {@code INSERT ... ON CONFLICT DO NOTHING}
 * seguido de {@code SELECT}, então duas transações no mesmo ciclo nunca deixam uma exceção de unicidade escapar. O
 * {@code ON CONFLICT} <b>não</b> nomeia a restrição: como o id é determinístico, a corrida colide na chave primária
 * ({@code pk_credit_card_invoices}) e em {@code (card_id, closing_date)} ao mesmo tempo, e o Postgres só trata como "nada
 * a fazer" o conflito da restrição nomeada (o da outra levantaria erro: achado pelo IT de concorrência de T52 CA2).
 */
public class InvoiceRowGateway implements InvoiceLookupGateway {

    /** Uma linha de fatura: o id, o cartão e o fechamento (a identidade de domínio). */
    public record InvoiceRow(UUID id, UUID cardId, LocalDate closingDate) {
    }

    private final JdbcTemplate jdbc;
    private final TimeProvider time;

    public InvoiceRowGateway(JdbcTemplate jdbc, TimeProvider time) {
        this.jdbc = jdbc;
        this.time = time;
    }

    /**
     * Id da linha da fatura que fecha em {@code closingDate}, criando-a se ainda não existe.
     *
     * @throws IllegalStateException se {@code closingDate} não é um fechamento do calendário do cartão (bug de ligação)
     */
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

    /** As linhas pedidas (ids sem linha ou de outro usuário não aparecem). */
    public Map<UUID, InvoiceRow> rowsByIds(UUID userId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Object> args = new java.util.ArrayList<>();
        args.add(userId);
        args.addAll(ids);
        return index(jdbc.query("SELECT id, card_id, closing_date FROM credit_card_invoices WHERE user_id = ? AND id IN ("
                + placeholders + ")", (rs, row) -> toRow(rs), args.toArray()));
    }

    /** Todas as linhas do usuário (carregamento único, S-79). */
    public Map<UUID, InvoiceRow> rowsOfUser(UUID userId) {
        return index(jdbc.query("SELECT id, card_id, closing_date FROM credit_card_invoices WHERE user_id = ?",
                (rs, row) -> toRow(rs), userId));
    }

    /** A linha pelo id (resolução de {@code GET /v1/invoices/{id}}, ADR-006 §6, passo 1). */
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

    /**
     * Alinha as linhas do cartão ao calendário dele (ADR-006 §7, T58 CA7/CA7b): atualiza as colunas de data das linhas que
     * mantêm o fechamento e remove as que o calendário não tem mais e que ninguém referencia.
     *
     * @throws IllegalStateException linha fora do calendário que ainda é referenciada (cadeia inconsistente, nunca silenciada)
     */
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

    /** Alguma parcela ou movimentação (ativa ou não: estorno e cancelamento não apagam histórico) aponta para a linha. */
    private boolean referenced(UUID userId, UUID invoiceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM transaction_installments WHERE user_id = ? AND invoice_id = ?)
                    OR EXISTS (SELECT 1 FROM transactions WHERE user_id = ? AND invoice_id = ?)""",
                Boolean.class, userId, invoiceId, userId, invoiceId));
    }
}
