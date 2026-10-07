package com.moneylog.infra.importing.gateways;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.moneylog.core.importing.app.gateways.ExistingEntriesGateway;
import com.moneylog.core.importing.domain.DedupKey;
import com.moneylog.core.importing.domain.ExistingEntry;
import com.moneylog.core.importing.domain.ImportDirection;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

/** Movimentações existentes vistas como {@link ExistingEntry}. Só ativas; sempre por {@code user_id}. */
public class ExistingEntriesGatewayImpl implements ExistingEntriesGateway {

    /**
     * Direção do ponto de vista da conta {@code ?} (o primeiro argumento do CASE). Transferência: saída na conta de origem, entrada no destino.
     * Investimento: aporte sai da conta, resgate entra. Ajuste: aumento entra, redução sai. Receita, devolução e dinheiro recebido entram; o resto sai.
     */
    private static final String ACCOUNT_DIRECTION = """
            CASE
              WHEN t.type = 'TRANSFER' THEN CASE WHEN t.account_id = ? THEN 'OUT' ELSE 'IN' END
              WHEN t.type IN ('INCOME', 'REFUND', 'MONEY_RECEIVED') THEN 'IN'
              WHEN t.type = 'INVESTMENT' THEN CASE WHEN t.direction = 'REDEMPTION' THEN 'IN' ELSE 'OUT' END
              WHEN t.type = 'ADJUSTMENT' THEN CASE WHEN t.direction = 'INCREASE' THEN 'IN' ELSE 'OUT' END
              ELSE 'OUT'
            END""";

    private final JdbcTemplate jdbc;

    public ExistingEntriesGatewayImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ExistingEntry> findForSource(UUID userId, UUID sourceId, boolean card, LocalDate from, LocalDate to) {
        if (card) {
            return jdbc.query("""
                    SELECT t.id, t.transaction_date, t.amount_cents, t.dedup_key, t.original_dedup_key, t.source,
                           CASE WHEN t.type = 'REFUND' THEN 'IN' ELSE 'OUT' END AS dir, t.type
                    FROM transactions t
                    WHERE t.user_id = ? AND t.status = 'ACTIVE' AND t.card_id = ? AND t.type IN ('CARD_PURCHASE', 'REFUND')
                      AND t.transaction_date BETWEEN ? AND ?""",
                    (rs, n) -> entry(rs, sourceId), userId, sourceId, from, to);
        }
        return jdbc.query("SELECT t.id, t.transaction_date, t.amount_cents, t.dedup_key, t.original_dedup_key, t.source, " + ACCOUNT_DIRECTION
                        + " AS dir, t.type FROM transactions t WHERE t.user_id = ? AND t.status = 'ACTIVE' "
                        + "AND (t.account_id = ? OR t.counterpart_account_id = ?) AND t.transaction_date BETWEEN ? AND ?",
                (rs, n) -> entry(rs, sourceId), sourceId, userId, sourceId, sourceId, from, to);
    }

    @Override
    public List<ExistingEntry> findInOtherAccounts(UUID userId, UUID excludingAccountId, LocalDate from, LocalDate to) {
        // transações de outras contas (despesas e receitas) e linhas ainda não aplicadas de lotes em revisão, também de outras contas
        List<ExistingEntry> transactions = jdbc.query("""
                SELECT t.id, t.account_id AS source_id, t.transaction_date AS day, t.amount_cents,
                       CASE WHEN t.type = 'INCOME' THEN 'IN' ELSE 'OUT' END AS dir
                FROM transactions t
                WHERE t.user_id = ? AND t.status = 'ACTIVE' AND t.type IN ('EXPENSE', 'INCOME') AND t.account_id IS NOT NULL
                  AND t.account_id <> ? AND t.transaction_date BETWEEN ? AND ?""", (rs, n) -> other(rs), userId, excludingAccountId, from, to);
        List<ExistingEntry> pending = jdbc.query("""
                SELECT l.id, l.source_id, l.line_date AS day, l.amount_cents, l.direction AS dir
                FROM import_lines l JOIN import_batches b ON b.id = l.batch_id AND b.user_id = l.user_id
                WHERE l.user_id = ? AND b.status = 'PENDING_REVIEW' AND b.account_id IS NOT NULL AND b.account_id <> ?
                  AND l.status IN ('PENDING', 'ACCEPTED') AND l.line_date BETWEEN ? AND ?""", (rs, n) -> other(rs), userId, excludingAccountId,
                from, to);
        java.util.ArrayList<ExistingEntry> all = new java.util.ArrayList<>(transactions);
        all.addAll(pending);
        return all;
    }

    @Override
    public boolean hasActiveOppositeLeg(UUID userId, UUID accountId, ImportDirection direction, Money amount, LocalDate date) {
        // linha de saída: a perna oposta é uma receita em outra conta; linha de entrada: uma despesa
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM transactions t
                WHERE t.user_id = ? AND t.status = 'ACTIVE' AND t.type = ? AND t.account_id IS NOT NULL AND t.account_id <> ?
                  AND t.amount_cents = ? AND t.transaction_date BETWEEN ? AND ?)""", Boolean.class, userId,
                direction == ImportDirection.OUT ? "INCOME" : "EXPENSE", accountId, amount.cents(), date.minusDays(1), date.plusDays(1)));
    }

    private static ExistingEntry entry(ResultSet rs, UUID sourceId) throws SQLException {
        String key = rs.getString("dedup_key");
        String original = rs.getString("original_dedup_key");
        String type = rs.getString("type");
        // transferência entra como "manual": a perna espelhada de um extrato vira possível duplicata dela, nunca despesa/receita sozinha
        boolean manual = "MANUAL".equals(rs.getString("source")) || "TRANSFER".equals(type);
        return new ExistingEntry(rs.getObject("id", UUID.class), sourceId, rs.getObject("transaction_date", LocalDate.class),
                Money.ofCents(rs.getLong("amount_cents"), CurrencyCode.BRL), ImportDirection.valueOf(rs.getString("dir")),
                key == null ? null : new DedupKey(key.strip()), original == null ? null : new DedupKey(original.strip()), manual, true);
    }

    private static ExistingEntry other(ResultSet rs) throws SQLException {
        return new ExistingEntry(rs.getObject("id", UUID.class), rs.getObject("source_id", UUID.class), rs.getObject("day", LocalDate.class),
                Money.ofCents(rs.getLong("amount_cents"), CurrencyCode.BRL), ImportDirection.valueOf(rs.getString("dir")), null, null, false, true);
    }
}
