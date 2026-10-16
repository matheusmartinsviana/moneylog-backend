package com.moneylog.infra.income.persistence;

import java.util.UUID;

import org.hibernate.annotations.Immutable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Immutable
@Table(name = "payroll_loan_applications")
public class PayrollLoanApplicationEntity {

    @Id
    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "debt_id", nullable = false, updatable = false)
    private UUID debtId;

    @Column(name = "principal_cents", nullable = false, updatable = false)
    private long principalCents;

    protected PayrollLoanApplicationEntity() {
    }

    public UUID getTransactionId() {
        return transactionId;
    }

    public UUID getDebtId() {
        return debtId;
    }

    public long getPrincipalCents() {
        return principalCents;
    }
}
