CREATE TABLE payroll_loan_applications (
    transaction_id  UUID        NOT NULL,
    user_id         UUID        NOT NULL,
    debt_id         UUID        NOT NULL,
    applied_cents   BIGINT      NOT NULL,
    interest_cents  BIGINT,
    principal_cents BIGINT      NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_payroll_loan_applications PRIMARY KEY (transaction_id),
    CONSTRAINT fk_payroll_loan_applications_transaction FOREIGN KEY (transaction_id, user_id) REFERENCES transactions (id, user_id),
    CONSTRAINT fk_payroll_loan_applications_debt FOREIGN KEY (debt_id, user_id) REFERENCES debts (id, user_id),
    CONSTRAINT ck_payroll_loan_applications_applied CHECK (applied_cents > 0),
    CONSTRAINT ck_payroll_loan_applications_interest CHECK (interest_cents IS NULL OR interest_cents BETWEEN 0 AND applied_cents),
    CONSTRAINT ck_payroll_loan_applications_principal CHECK (principal_cents = applied_cents - COALESCE(interest_cents, 0))
);

CREATE INDEX ix_payroll_loan_applications_debt ON payroll_loan_applications (user_id, debt_id);
