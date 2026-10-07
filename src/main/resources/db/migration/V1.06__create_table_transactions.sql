-- Movimentações (D1 §2.4). Um registro por movimentação; os efeitos financeiros ficam em ledger_postings.
-- Colunas específicas de tipo são anuláveis e validadas pelo domínio (fábricas por tipo); o banco reforça
-- enums, valor positivo, estorno consistente e unicidades.
CREATE TABLE transactions (
    id                      UUID         NOT NULL,
    user_id                 UUID         NOT NULL,
    type                    VARCHAR(20)  NOT NULL,
    status                  VARCHAR(16)  NOT NULL,
    amount_cents            BIGINT       NOT NULL,
    currency                CHAR(3)      NOT NULL,
    transaction_date        DATE         NOT NULL,
    description             VARCHAR(200),
    notes                   TEXT,
    payment_method          VARCHAR(16),
    -- conta principal: origem da transferência, conta CASH do investimento, conta da despesa/receita etc.
    account_id              UUID,
    -- contrapartida: destino da transferência ou conta de investimento do INVESTMENT
    counterpart_account_id  UUID,
    card_id                 UUID,
    category_id             UUID,
    -- fatura do INVOICE_PAYMENT e do REFUND no cartão (crédito); parcelas guardam a fatura em transaction_installments
    invoice_id              UUID,
    late_entry              BOOLEAN      NOT NULL DEFAULT FALSE,
    installments            SMALLINT,
    debt_id                 UUID,
    refund_of_id            UUID,
    direction               VARCHAR(16),
    adjustment_kind         VARCHAR(16),
    reason                  VARCHAR(500),
    source                  VARCHAR(16)  NOT NULL,
    import_batch_id         UUID,
    -- chave efetiva (participa da unicidade). Na duplicata forçada é a chave derivada do lote (DedupKey.forcedIn,
    -- S-75 revisada): única, determinística e protege contra aplicar o mesmo lote duas vezes
    dedup_key               CHAR(64),
    -- chave original da linha importada (sem unicidade). O classificador compara a linha nova com dedup_key E com
    -- original_dedup_key das ativas, para a reimportação reconhecer também a duplicata forçada
    original_dedup_key      CHAR(64),
    recurrence_id           UUID,
    occurrence_date         DATE,
    void_reason             VARCHAR(500),
    voided_at               TIMESTAMPTZ,
    created_at              TIMESTAMPTZ  NOT NULL,
    updated_at              TIMESTAMPTZ  NOT NULL,
    version                 BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_transactions PRIMARY KEY (id),
    CONSTRAINT fk_transactions_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_transactions_account FOREIGN KEY (account_id) REFERENCES accounts (id),
    CONSTRAINT fk_transactions_counterpart_account FOREIGN KEY (counterpart_account_id) REFERENCES accounts (id),
    CONSTRAINT fk_transactions_card FOREIGN KEY (card_id) REFERENCES credit_cards (id),
    CONSTRAINT fk_transactions_category FOREIGN KEY (category_id) REFERENCES categories (id),
    CONSTRAINT fk_transactions_invoice FOREIGN KEY (invoice_id) REFERENCES credit_card_invoices (id),
    CONSTRAINT fk_transactions_debt FOREIGN KEY (debt_id) REFERENCES debts (id),
    CONSTRAINT fk_transactions_refund_of FOREIGN KEY (refund_of_id) REFERENCES transactions (id),
    CONSTRAINT ck_transactions_type CHECK (type IN ('EXPENSE', 'INCOME', 'TRANSFER', 'INVESTMENT', 'LOAN_GIVEN',
        'MONEY_RECEIVED', 'DEBT_PAYMENT', 'CARD_PURCHASE', 'INVOICE_PAYMENT', 'REFUND', 'ADJUSTMENT')),
    CONSTRAINT ck_transactions_status CHECK (status IN ('ACTIVE', 'VOIDED')),
    CONSTRAINT ck_transactions_amount_positive CHECK (amount_cents > 0),
    CONSTRAINT ck_transactions_currency CHECK (currency = 'BRL'),
    CONSTRAINT ck_transactions_payment_method CHECK (
        payment_method IS NULL OR payment_method IN ('CASH', 'DEBIT', 'PIX', 'TRANSFER', 'BOLETO', 'OTHER')),
    CONSTRAINT ck_transactions_direction CHECK (
        direction IS NULL OR direction IN ('CONTRIBUTION', 'REDEMPTION', 'INCREASE', 'DECREASE')),
    CONSTRAINT ck_transactions_adjustment_kind CHECK (adjustment_kind IS NULL OR adjustment_kind IN ('CORRECTION', 'YIELD')),
    CONSTRAINT ck_transactions_installments CHECK (installments IS NULL OR installments BETWEEN 1 AND 36),
    CONSTRAINT ck_transactions_card_purchase_installments CHECK ((type = 'CARD_PURCHASE') = (installments IS NOT NULL)),
    CONSTRAINT ck_transactions_source CHECK (source IN ('MANUAL', 'IMPORT', 'RECURRENCE')),
    CONSTRAINT ck_transactions_import_batch CHECK ((source = 'IMPORT') = (import_batch_id IS NOT NULL)),
    CONSTRAINT ck_transactions_recurrence CHECK (
        (source = 'RECURRENCE') = (recurrence_id IS NOT NULL) AND (recurrence_id IS NULL) = (occurrence_date IS NULL)),
    CONSTRAINT ck_transactions_voided CHECK (
        (status = 'ACTIVE' AND voided_at IS NULL AND void_reason IS NULL)
        OR (status = 'VOIDED' AND voided_at IS NOT NULL AND void_reason IS NOT NULL))
);

CREATE UNIQUE INDEX uq_transactions_user_dedup_active ON transactions (user_id, dedup_key)
    WHERE status = 'ACTIVE' AND dedup_key IS NOT NULL;
-- S-49: a ocorrência estornada pode ser realizada de novo (o índice vale só entre as ativas).
CREATE UNIQUE INDEX uq_transactions_recurrence_occurrence ON transactions (recurrence_id, occurrence_date)
    WHERE status = 'ACTIVE' AND recurrence_id IS NOT NULL;
CREATE INDEX ix_transactions_user_date ON transactions (user_id, transaction_date);
CREATE INDEX ix_transactions_user_original_dedup ON transactions (user_id, original_dedup_key)
    WHERE original_dedup_key IS NOT NULL;

ALTER TABLE debts
    ADD CONSTRAINT fk_debts_origin_transaction FOREIGN KEY (origin_transaction_id) REFERENCES transactions (id)
        DEFERRABLE INITIALLY DEFERRED;
