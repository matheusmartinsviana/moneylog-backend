-- Parcelas da CARD_PURCHASE (D1 §2.5.2). PENDING/PAID/OVERDUE são derivados do status da fatura;
-- o único status gravado é o cancelamento (canceled_at).
CREATE TABLE transaction_installments (
    id                   UUID        NOT NULL,
    user_id              UUID        NOT NULL,
    transaction_id       UUID        NOT NULL,
    number               SMALLINT    NOT NULL,
    total_installments   SMALLINT    NOT NULL,
    amount_cents         BIGINT      NOT NULL,
    currency             CHAR(3)     NOT NULL,
    competence           CHAR(7)     NOT NULL,
    effective_date       DATE        NOT NULL,
    invoice_id           UUID        NOT NULL,
    late_entry           BOOLEAN     NOT NULL DEFAULT FALSE,
    canceled_at          TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL,
    updated_at           TIMESTAMPTZ NOT NULL,
    version              BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT pk_transaction_installments PRIMARY KEY (id),
    CONSTRAINT fk_installments_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_installments_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id),
    CONSTRAINT fk_installments_invoice FOREIGN KEY (invoice_id) REFERENCES credit_card_invoices (id),
    CONSTRAINT uq_installments_transaction_number UNIQUE (transaction_id, number),
    CONSTRAINT ck_installments_amount_positive CHECK (amount_cents > 0),
    CONSTRAINT ck_installments_currency CHECK (currency = 'BRL'),
    CONSTRAINT ck_installments_total CHECK (total_installments BETWEEN 1 AND 36),
    CONSTRAINT ck_installments_number CHECK (number BETWEEN 1 AND total_installments),
    CONSTRAINT ck_installments_competence CHECK (competence ~ '^[0-9]{4}-(0[1-9]|1[0-2])$')
);

CREATE INDEX ix_installments_invoice ON transaction_installments (invoice_id);
