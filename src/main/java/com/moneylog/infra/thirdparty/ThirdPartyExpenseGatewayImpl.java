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
        jdbc.update("INSERT INTO third_party_expenses (id, user_id, transaction_id, person_id, note, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                e.id(), e.userId(), e.transactionId(), e.personId(), e.note(), Timestamp.from(time.now()));
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

    private static ThirdPartyExpense map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ThirdPartyExpense(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getObject("transaction_id", UUID.class), rs.getObject("person_id", UUID.class), rs.getString("note"));
    }
}
