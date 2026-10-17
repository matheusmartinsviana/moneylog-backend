CREATE TABLE receivable_expected_months (
    id             UUID        NOT NULL,
    user_id        UUID        NOT NULL,
    transaction_id UUID        NOT NULL,
    installment_id UUID        NOT NULL,
    person_id      UUID        NOT NULL,
    expected_month CHAR(7)     NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    updated_at     TIMESTAMPTZ NOT NULL,
    version        BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT pk_receivable_expected_months PRIMARY KEY (id),
    CONSTRAINT fk_receivable_expected_months_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_receivable_expected_months_transaction FOREIGN KEY (transaction_id, user_id) REFERENCES transactions (id, user_id),
    CONSTRAINT fk_receivable_expected_months_person FOREIGN KEY (person_id, user_id) REFERENCES persons (id, user_id),
    CONSTRAINT uq_receivable_expected_months UNIQUE (user_id, installment_id, person_id),
    CONSTRAINT ck_receivable_expected_months_format CHECK (expected_month ~ '^(20[0-9]{2}|2100)-(0[1-9]|1[0-2])$')
);

CREATE INDEX ix_receivable_expected_months_user ON receivable_expected_months (user_id);
