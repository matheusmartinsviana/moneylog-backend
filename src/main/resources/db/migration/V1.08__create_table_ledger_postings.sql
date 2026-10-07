-- Razão de partidas dobradas (D1 §1.1, ADR-002 + adendo). Cada linha é um posting com origem (S-45).
-- As lentes leem só postings active, sem JOIN com movimentações. Posting nunca é editado nem reativado:
-- a correção desativa o grupo antigo (active = false) e grava um grupo novo.
CREATE TABLE ledger_postings (
    id                   UUID         NOT NULL,
    user_id              UUID         NOT NULL,
    source_type          VARCHAR(20)  NOT NULL,
    source_id            UUID         NOT NULL,
    transaction_id       UUID,
    installment_id       UUID,
    book_type            VARCHAR(16)  NOT NULL,
    book_id              UUID,
    competence           CHAR(7)      NOT NULL,
    effective_date       DATE         NOT NULL,
    amount_cents         BIGINT       NOT NULL,
    currency             CHAR(3)      NOT NULL,
    active               BOOLEAN      NOT NULL,
    deactivated_at       TIMESTAMPTZ,
    deactivation_reason  VARCHAR(500),
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_ledger_postings PRIMARY KEY (id),
    CONSTRAINT fk_ledger_postings_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_ledger_postings_transaction FOREIGN KEY (transaction_id) REFERENCES transactions (id),
    CONSTRAINT fk_ledger_postings_installment FOREIGN KEY (installment_id) REFERENCES transaction_installments (id),
    CONSTRAINT ck_ledger_postings_source_type CHECK (
        source_type IN ('TRANSACTION', 'ACCOUNT_OPENING', 'DEBT_RECOGNITION', 'DEBT_CANCELLATION')),
    CONSTRAINT ck_ledger_postings_transaction_source CHECK (
        ((source_type = 'TRANSACTION') = (transaction_id IS NOT NULL))
        AND (installment_id IS NULL OR source_type = 'TRANSACTION')
        AND (transaction_id IS NULL OR transaction_id = source_id)),
    CONSTRAINT ck_ledger_postings_book_type CHECK (
        book_type IN ('ACCOUNT', 'CARD', 'RECEIVABLE', 'PAYABLE', 'EXPENSE', 'INCOME', 'EQUITY')),
    CONSTRAINT ck_ledger_postings_book_id CHECK ((book_type = 'EQUITY') = (book_id IS NULL)),
    CONSTRAINT ck_ledger_postings_amount_not_zero CHECK (amount_cents <> 0),
    CONSTRAINT ck_ledger_postings_currency CHECK (currency = 'BRL'),
    CONSTRAINT ck_ledger_postings_competence CHECK (competence = to_char(effective_date, 'YYYY-MM')),
    CONSTRAINT ck_ledger_postings_deactivation CHECK (
        (active AND deactivated_at IS NULL AND deactivation_reason IS NULL)
        OR (NOT active AND deactivated_at IS NOT NULL AND deactivation_reason IS NOT NULL))
);

CREATE INDEX ix_ledger_postings_book ON ledger_postings (user_id, book_type, book_id, effective_date) WHERE active;
CREATE INDEX ix_ledger_postings_competence ON ledger_postings (user_id, book_type, competence) WHERE active;
CREATE INDEX ix_ledger_postings_source ON ledger_postings (source_type, source_id);
