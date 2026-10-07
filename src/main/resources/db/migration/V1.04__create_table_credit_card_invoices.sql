-- Faturas (D1 §2.5.3). Identidade = (card_id, closing_date) (S-13); reference_month é só rótulo e pode repetir.
-- NÃO existe coluna de status: FUTURE/OPEN/CLOSED/OVERDUE/PAID é derivado na leitura (S-37).
-- total, paid, carryIn, outstanding e creditCarry também são calculados (itens, pagamentos e cadeia do cartão).
CREATE TABLE credit_card_invoices (
    id               UUID        NOT NULL,
    user_id          UUID        NOT NULL,
    card_id          UUID        NOT NULL,
    period_start     DATE        NOT NULL,
    closing_date     DATE        NOT NULL,
    due_date         DATE        NOT NULL,
    reference_month  CHAR(7)     NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    updated_at       TIMESTAMPTZ NOT NULL,
    version          BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT pk_credit_card_invoices PRIMARY KEY (id),
    CONSTRAINT fk_credit_card_invoices_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_credit_card_invoices_card FOREIGN KEY (card_id) REFERENCES credit_cards (id),
    CONSTRAINT uq_credit_card_invoices_card_closing UNIQUE (card_id, closing_date),
    CONSTRAINT ck_credit_card_invoices_period CHECK (period_start < closing_date),
    CONSTRAINT ck_credit_card_invoices_due_after_closing CHECK (due_date > closing_date),
    CONSTRAINT ck_credit_card_invoices_reference CHECK (reference_month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$')
);

CREATE INDEX ix_credit_card_invoices_user_due ON credit_card_invoices (user_id, due_date);
