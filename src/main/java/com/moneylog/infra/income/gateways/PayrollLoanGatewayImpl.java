package com.moneylog.infra.income.gateways;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.moneylog.core.income.app.PayrollLoanApplication;
import com.moneylog.core.income.app.PayrollLoanPlan;
import com.moneylog.core.income.app.PayrollLoanPlanLink;
import com.moneylog.core.income.app.gateways.PayrollLoanGateway;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.TimeProvider;

public class PayrollLoanGatewayImpl implements PayrollLoanGateway {

    private final JdbcTemplate jdbc;
    private final IdGenerator ids;
    private final TimeProvider time;

    public PayrollLoanGatewayImpl(JdbcTemplate jdbc, IdGenerator ids, TimeProvider time) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public void savePlan(UUID userId, UUID recurrenceId, PayrollLoanPlan plan) {
        jdbc.update("INSERT INTO income_occurrence_loans (id, user_id, recurrence_id, debt_id, applied_cents, interest_cents, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)", ids.next(), userId, recurrenceId, plan.debtId(), plan.applied().cents(),
                plan.interest() == null ? null : plan.interest().cents(), Timestamp.from(time.now()));
    }

    @Override
    public Optional<PayrollLoanPlan> findPlan(UUID userId, UUID recurrenceId) {
        List<PayrollLoanPlan> found = jdbc.query("SELECT debt_id, applied_cents, interest_cents FROM income_occurrence_loans "
                        + "WHERE user_id = ? AND recurrence_id = ?",
                (rs, i) -> new PayrollLoanPlan(rs.getObject(1, UUID.class), Money.ofCents(rs.getLong(2), CurrencyCode.BRL),
                        rs.getObject(3) == null ? null : Money.ofCents(rs.getLong(3), CurrencyCode.BRL)),
                userId, recurrenceId);
        return found.stream().findFirst();
    }

    @Override
    public List<PayrollLoanPlanLink> findAllPlans(UUID userId) {
        return jdbc.query("SELECT recurrence_id, debt_id, applied_cents, interest_cents FROM income_occurrence_loans "
                        + "WHERE user_id = ? ORDER BY created_at, id",
                (rs, i) -> new PayrollLoanPlanLink(rs.getObject(1, UUID.class),
                        new PayrollLoanPlan(rs.getObject(2, UUID.class), Money.ofCents(rs.getLong(3), CurrencyCode.BRL),
                                rs.getObject(4) == null ? null : Money.ofCents(rs.getLong(4), CurrencyCode.BRL))),
                userId);
    }

    @Override
    public List<PayrollLoanApplication> findAllApplications(UUID userId) {
        return jdbc.query("SELECT transaction_id, debt_id, applied_cents, interest_cents, principal_cents "
                        + "FROM payroll_loan_applications WHERE user_id = ? ORDER BY created_at, transaction_id",
                (rs, i) -> new PayrollLoanApplication(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        Money.ofCents(rs.getLong(3), CurrencyCode.BRL),
                        rs.getObject(4) == null ? null : Money.ofCents(rs.getLong(4), CurrencyCode.BRL),
                        Money.ofCents(rs.getLong(5), CurrencyCode.BRL)),
                userId);
    }

    @Override
    public void saveApplication(UUID userId, UUID transactionId, UUID debtId, Money applied, Money interest, Money principal) {
        jdbc.update("INSERT INTO payroll_loan_applications (transaction_id, user_id, debt_id, applied_cents, interest_cents, "
                        + "principal_cents, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)", transactionId, userId, debtId, applied.cents(),
                interest == null ? null : interest.cents(), principal.cents(), Timestamp.from(time.now()));
    }
}
