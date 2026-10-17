package com.moneylog.infra.card.gateways;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.moneylog.core.card.app.gateways.InvoiceDocumentGateway;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.TimeProvider;

public class InvoiceDocumentGatewayImpl implements InvoiceDocumentGateway {

    private final JdbcTemplate jdbc;
    private final InvoiceRowGateway rows;
    private final TimeProvider time;

    public InvoiceDocumentGatewayImpl(JdbcTemplate jdbc, InvoiceRowGateway rows, TimeProvider time) {
        this.jdbc = jdbc;
        this.rows = rows;
        this.time = time;
    }

    @Override
    public Stored find(UUID userId, UUID invoiceId) {
        return jdbc.query("SELECT document_amount_cents, version FROM credit_card_invoices WHERE user_id = ? AND id = ?",
                (rs, i) -> stored(rs.getObject("document_amount_cents", Long.class), rs.getLong("version")), userId, invoiceId)
                .stream().findFirst().orElse(new Stored(null, 0));
    }

    @Override
    public Map<UUID, Money> amountsOf(UUID userId) {
        Map<UUID, Money> result = new HashMap<>();
        jdbc.query("SELECT id, document_amount_cents FROM credit_card_invoices WHERE user_id = ? AND document_amount_cents IS NOT NULL",
                rs -> {
                    result.put(rs.getObject("id", UUID.class), Money.ofCents(rs.getLong("document_amount_cents"), CurrencyCode.BRL));
                }, userId);
        return result;
    }

    @Override
    public Stored change(UUID userId, CreditCard card, LocalDate closingDate, Money amountOrNull) {
        UUID invoiceId = rows.findOrCreate(userId, card, closingDate);
        jdbc.update("UPDATE credit_card_invoices SET document_amount_cents = ?, version = version + 1, updated_at = ? WHERE id = ? AND user_id = ?",
                amountOrNull == null ? null : amountOrNull.cents(), Timestamp.from(time.now()), invoiceId, userId);
        return find(userId, invoiceId);
    }

    private static Stored stored(Long cents, long version) {
        return new Stored(cents == null ? null : Money.ofCents(cents, CurrencyCode.BRL), version);
    }
}
