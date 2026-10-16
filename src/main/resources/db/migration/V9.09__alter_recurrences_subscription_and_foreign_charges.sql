ALTER TABLE recurring_transactions
    ADD COLUMN original_amount_cents BIGINT,
    ADD COLUMN original_currency     CHAR(3),
    ADD COLUMN reference_fx_rate     NUMERIC(13, 6),
    ADD COLUMN iof_bps               INTEGER,
    ADD COLUMN is_subscription       BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN scope                 VARCHAR(16) NOT NULL DEFAULT 'PERSONAL',
    ADD COLUMN channel               VARCHAR(60),
    ADD COLUMN overlap_group         VARCHAR(60),
    ADD COLUMN last_used_on          DATE;

ALTER TABLE recurring_transactions ADD CONSTRAINT ck_recurring_transactions_scope CHECK (scope IN ('PERSONAL', 'COMPANY'));
ALTER TABLE recurring_transactions ADD CONSTRAINT ck_recurring_transactions_foreign CHECK (
    (original_amount_cents IS NULL AND original_currency IS NULL AND reference_fx_rate IS NULL AND iof_bps IS NULL)
    OR (original_amount_cents > 0 AND original_currency IS NOT NULL AND original_currency <> 'BRL'
        AND reference_fx_rate > 0 AND iof_bps BETWEEN 0 AND 10000));

UPDATE recurring_transactions SET is_subscription = TRUE WHERE type = 'CARD_PURCHASE';

CREATE INDEX ix_recurring_transactions_subscription ON recurring_transactions (user_id, scope) WHERE is_subscription;

CREATE TABLE transaction_foreign_charges (
    transaction_id        UUID          NOT NULL,
    user_id               UUID          NOT NULL,
    original_amount_cents BIGINT        NOT NULL,
    original_currency     CHAR(3)       NOT NULL,
    fx_rate               NUMERIC(13, 6) NOT NULL,
    iof_bps               INTEGER       NOT NULL,
    created_at            TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_transaction_foreign_charges PRIMARY KEY (transaction_id),
    CONSTRAINT fk_transaction_foreign_charges_transaction FOREIGN KEY (transaction_id, user_id)
        REFERENCES transactions (id, user_id),
    CONSTRAINT ck_transaction_foreign_charges_values CHECK (
        original_amount_cents > 0 AND original_currency <> 'BRL' AND fx_rate > 0 AND iof_bps BETWEEN 0 AND 10000)
);

CREATE INDEX ix_transaction_foreign_charges_user ON transaction_foreign_charges (user_id);
