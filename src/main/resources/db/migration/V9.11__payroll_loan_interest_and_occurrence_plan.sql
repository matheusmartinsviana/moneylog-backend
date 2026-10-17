ALTER TABLE income_profile_deductions ADD COLUMN interest_cents BIGINT;

ALTER TABLE income_profile_deductions ADD CONSTRAINT ck_income_profile_deductions_interest CHECK (
    interest_cents IS NULL OR (kind = 'LOAN' AND interest_cents >= 0));

CREATE TABLE income_occurrence_loans (
    id             UUID        NOT NULL,
    user_id        UUID        NOT NULL,
    recurrence_id  UUID        NOT NULL,
    debt_id        UUID        NOT NULL,
    applied_cents  BIGINT      NOT NULL,
    interest_cents BIGINT,
    created_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_income_occurrence_loans PRIMARY KEY (id),
    CONSTRAINT uq_income_occurrence_loans_recurrence UNIQUE (recurrence_id),
    CONSTRAINT fk_income_occurrence_loans_recurrence FOREIGN KEY (recurrence_id, user_id) REFERENCES recurring_transactions (id, user_id),
    CONSTRAINT fk_income_occurrence_loans_debt FOREIGN KEY (debt_id, user_id) REFERENCES debts (id, user_id),
    CONSTRAINT ck_income_occurrence_loans_applied CHECK (applied_cents > 0),
    CONSTRAINT ck_income_occurrence_loans_interest CHECK (interest_cents IS NULL OR interest_cents BETWEEN 0 AND applied_cents)
);

CREATE INDEX ix_income_occurrence_loans_debt ON income_occurrence_loans (user_id, debt_id);
