package com.moneylog.infra.thirdparty;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.thirdparty.app.gateways.ThirdPartyExpenseGateway;
import com.moneylog.core.thirdparty.domain.ReceiptSettlement;
import com.moneylog.core.thirdparty.domain.ThirdPartyExpense;
import com.moneylog.core.thirdparty.domain.ThirdPartyReceipt;

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
    public ThirdPartyReceipt insertReceipt(ThirdPartyReceipt r) {
        jdbc.update("INSERT INTO third_party_receipts (id, user_id, person_id, transaction_id, amount_cents, received_on, note, created_at, account_id, settlement, ledgered) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                r.id(), r.userId(), r.personId(), r.transactionId(), r.amount().cents(),
                r.receivedOn() == null ? null : java.sql.Date.valueOf(r.receivedOn()), r.note(), Timestamp.from(time.now()),
                r.accountId(), r.settlement().name(), r.ledgered());
        return r;
    }

    @Override
    public List<ThirdPartyReceipt> findAllReceipts(UUID userId) {
        return jdbc.query("SELECT * FROM third_party_receipts WHERE user_id = ? ORDER BY created_at, id", (rs, i) -> mapReceipt(rs), userId);
    }

    @Override
    public Optional<ThirdPartyReceipt> findReceipt(UUID userId, UUID id) {
        return jdbc.query("SELECT * FROM third_party_receipts WHERE user_id = ? AND id = ?", (rs, i) -> mapReceipt(rs), userId, id)
                .stream().findFirst();
    }

    @Override
    public void ledgerReceipt(UUID userId, UUID id, java.time.LocalDate receivedOn) {
        jdbc.update("UPDATE third_party_receipts SET ledgered = TRUE, received_on = ? WHERE user_id = ? AND id = ? AND ledgered = FALSE",
                java.sql.Date.valueOf(receivedOn), userId, id);
    }

    @Override
    public boolean deleteReceipt(UUID userId, UUID id) {
        return jdbc.update("DELETE FROM third_party_receipts WHERE user_id = ? AND id = ?", userId, id) > 0;
    }

    @Override
    public Optional<ThirdPartyExpense> findById(UUID userId, UUID id) {
        return jdbc.query("SELECT * FROM third_party_expenses WHERE user_id = ? AND id = ?", (rs, i) -> map(rs), userId, id).stream().findFirst();
    }

    @Override
    public void delete(UUID userId, UUID id) {
        jdbc.update("DELETE FROM third_party_expenses WHERE user_id = ? AND id = ?", userId, id);
    }

    @Override
    public List<com.moneylog.core.thirdparty.domain.ReceivableExpectedMonth> findExpectedMonths(UUID userId) {
        return jdbc.query("SELECT * FROM receivable_expected_months WHERE user_id = ? ORDER BY created_at, id", (rs, i) -> mapExpected(rs), userId);
    }

    @Override
    public Optional<com.moneylog.core.thirdparty.domain.ReceivableExpectedMonth> findExpectedMonth(UUID userId, UUID installmentId, UUID personId) {
        return jdbc.query("SELECT * FROM receivable_expected_months WHERE user_id = ? AND installment_id = ? AND person_id = ?",
                (rs, i) -> mapExpected(rs), userId, installmentId, personId).stream().findFirst();
    }

    @Override
    public com.moneylog.core.thirdparty.domain.ReceivableExpectedMonth saveExpectedMonth(UUID userId, UUID transactionId, UUID installmentId,
                                                                                         UUID personId, com.moneylog.core.sk.time.Competence month,
                                                                                         Long expectedVersion) {
        Optional<com.moneylog.core.thirdparty.domain.ReceivableExpectedMonth> existing = findExpectedMonth(userId, installmentId, personId);
        Timestamp now = Timestamp.from(time.now());
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO receivable_expected_months (id, user_id, transaction_id, installment_id, person_id, expected_month, created_at, updated_at, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)",
                    UUID.randomUUID(), userId, transactionId, installmentId, personId, month.toString(), now, now);
        } else {
            long current = existing.get().version();
            if (expectedVersion != null && expectedVersion != current) {
                throw new com.moneylog.core.sk.exception.VersionConflictException(expectedVersion, current);
            }
            int rows = jdbc.update("UPDATE receivable_expected_months SET expected_month = ?, updated_at = ?, version = version + 1 WHERE user_id = ? AND installment_id = ? AND person_id = ? AND version = ?",
                    month.toString(), now, userId, installmentId, personId, current);
            if (rows == 0) {
                throw new com.moneylog.core.sk.exception.VersionConflictException();
            }
        }
        return findExpectedMonth(userId, installmentId, personId).orElseThrow();
    }

    @Override
    public void deleteExpectedMonth(UUID userId, UUID installmentId, UUID personId) {
        jdbc.update("DELETE FROM receivable_expected_months WHERE user_id = ? AND installment_id = ? AND person_id = ?", userId, installmentId, personId);
    }

    private static com.moneylog.core.thirdparty.domain.ReceivableExpectedMonth mapExpected(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new com.moneylog.core.thirdparty.domain.ReceivableExpectedMonth(rs.getObject("transaction_id", UUID.class),
                rs.getObject("installment_id", UUID.class), rs.getObject("person_id", UUID.class),
                com.moneylog.core.sk.time.Competence.parse(rs.getString("expected_month")), rs.getLong("version"));
    }

    private static ThirdPartyExpense map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ThirdPartyExpense(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getObject("transaction_id", UUID.class), rs.getObject("person_id", UUID.class), rs.getString("note"),
                rs.getObject("amount_cents") == null ? null : com.moneylog.core.sk.money.Money.ofCents(rs.getLong("amount_cents"), com.moneylog.core.sk.money.CurrencyCode.BRL));
    }

    private static ThirdPartyReceipt mapReceipt(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ThirdPartyReceipt(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getObject("person_id", UUID.class),
                rs.getObject("transaction_id", UUID.class), com.moneylog.core.sk.money.Money.ofCents(rs.getLong("amount_cents"), com.moneylog.core.sk.money.CurrencyCode.BRL),
                rs.getDate("received_on") == null ? null : rs.getDate("received_on").toLocalDate(), rs.getString("note"),
                rs.getObject("account_id", UUID.class), ReceiptSettlement.valueOf(rs.getString("settlement")), rs.getBoolean("ledgered"));
    }
}
