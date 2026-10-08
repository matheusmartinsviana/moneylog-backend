package com.moneylog.infra.thirdparty;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.thirdparty.app.gateways.ThirdPartyExpenseGateway;
import com.moneylog.core.thirdparty.domain.ThirdPartyExpense;

/** {@code third_party_expenses} (V8.07) por JDBC; sempre filtra por usuário. */
public class ThirdPartyExpenseGatewayImpl implements ThirdPartyExpenseGateway {

    private final JdbcTemplate jdbc;
    private final TimeProvider time;

    public ThirdPartyExpenseGatewayImpl(JdbcTemplate jdbc, TimeProvider time) {
        this.jdbc = jdbc;
        this.time = time;
    }

    @Override
    public ThirdPartyExpense insert(ThirdPartyExpense e) {
        jdbc.update("INSERT INTO third_party_expenses (id, user_id, transaction_id, person_id, note, amount_cents, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                e.id(), e.userId(), e.transactionId(), e.personId(), e.note(), e.amount() == null ? null : e.amount().cents(), Timestamp.from(time.now()));
        return e;
    }

    @Override
    public Optional<ThirdPartyExpense> findByTransaction(UUID userId, UUID transactionId) {
        return jdbc.query("SELECT * FROM third_party_expenses WHERE user_id = ? AND transaction_id = ?", (rs, i) -> map(rs), userId,
                transactionId).stream().findFirst();
    }

    @Override
    public List<ThirdPartyExpense> findAll(UUID userId) {
        return jdbc.query("SELECT * FROM third_party_expenses WHERE user_id = ? ORDER BY created_at, id", (rs, i) -> map(rs), userId);
    }

    @Override
    public com.moneylog.core.thirdparty.domain.ThirdPartyReceipt insertReceipt(com.moneylog.core.thirdparty.domain.ThirdPartyReceipt r) {
        jdbc.update("INSERT INTO third_party_receipts (id, user_id, person_id, transaction_id, amount_cents, received_on, note, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                r.id(), r.userId(), r.personId(), r.transactionId(), r.amount().cents(),
                r.receivedOn() == null ? null : java.sql.Date.valueOf(r.receivedOn()), r.note(), Timestamp.from(time.now()));
        return r;
    }

    @Override
    public List<com.moneylog.core.thirdparty.domain.ThirdPartyReceipt> findAllReceipts(UUID userId) {
        return jdbc.query("SELECT * FROM third_party_receipts WHERE user_id = ? ORDER BY created_at, id", (rs, i) -> new com.moneylog.core.thirdparty.domain.ThirdPartyReceipt(
                rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getObject("person_id", UUID.class),
                rs.getObject("transaction_id", UUID.class), com.moneylog.core.sk.money.Money.ofCents(rs.getLong("amount_cents"), com.moneylog.core.sk.money.CurrencyCode.BRL),
                rs.getDate("received_on") == null ? null : rs.getDate("received_on").toLocalDate(), rs.getString("note")), userId);
    }

    @Override
    public Optional<ThirdPartyExpense> findById(UUID userId, UUID id) {
        return jdbc.query("SELECT * FROM third_party_expenses WHERE user_id = ? AND id = ?", (rs, i) -> map(rs), userId, id).stream().findFirst();
    }

    @Override
    public void delete(UUID userId, UUID id) {
        jdbc.update("DELETE FROM third_party_expenses WHERE user_id = ? AND id = ?", userId, id);
    }

    private static ThirdPartyExpense map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ThirdPartyExpense(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getObject("transaction_id", UUID.class), rs.getObject("person_id", UUID.class), rs.getString("note"),
                rs.getObject("amount_cents") == null ? null : com.moneylog.core.sk.money.Money.ofCents(rs.getLong("amount_cents"), com.moneylog.core.sk.money.CurrencyCode.BRL));
    }
}
