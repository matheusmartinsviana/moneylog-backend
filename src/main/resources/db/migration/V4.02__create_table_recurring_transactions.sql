-- Recorrências (D1 §2.7, T79). O modelo é gravado aqui; as ocorrências realizadas NÃO são um dado à parte: são as movimentações
-- ativas com source RECURRENCE e o par (recurrence_id, occurrence_date), único entre as ativas (uq_transactions_recurrence_occurrence,
-- S-49), então estornar a movimentação devolve a ocorrência às pendentes. Só as puladas têm tabela (recurrence_skips).
-- FKs compostas (id, user_id) como nas demais (S-76).
CREATE TABLE recurring_transactions (
    id               UUID         NOT NULL,
    user_id          UUID         NOT NULL,
    type             VARCHAR(20)  NOT NULL,
    amount_cents     BIGINT       NOT NULL,
    currency         CHAR(3)      NOT NULL,
    description      VARCHAR(200),
    category_id      UUID         NOT NULL,
    account_id       UUID,
    card_id          UUID,
    payment_method   VARCHAR(16),
    frequency        VARCHAR(16)  NOT NULL,
    interval_count   INTEGER      NOT NULL DEFAULT 1,
    interval_unit    VARCHAR(16),
    start_date       DATE         NOT NULL,
    end_date         DATE,
    max_occurrences  INTEGER,
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    version          BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_recurring_transactions PRIMARY KEY (id),
    CONSTRAINT uq_recurring_transactions_id_user UNIQUE (id, user_id),
    CONSTRAINT fk_recurring_transactions_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_recurring_transactions_category_id_user FOREIGN KEY (category_id, user_id) REFERENCES categories (id, user_id),
    CONSTRAINT fk_recurring_transactions_account_id_user FOREIGN KEY (account_id, user_id) REFERENCES accounts (id, user_id),
    CONSTRAINT fk_recurring_transactions_card_id_user FOREIGN KEY (card_id, user_id) REFERENCES credit_cards (id, user_id),
    CONSTRAINT ck_recurring_transactions_type CHECK (type IN ('EXPENSE', 'INCOME', 'CARD_PURCHASE')),
    CONSTRAINT ck_recurring_transactions_amount_positive CHECK (amount_cents > 0),
    CONSTRAINT ck_recurring_transactions_currency CHECK (currency = 'BRL'),
    CONSTRAINT ck_recurring_transactions_payment_method CHECK (
        payment_method IS NULL OR payment_method IN ('CASH', 'DEBIT', 'PIX', 'TRANSFER', 'BOLETO', 'OTHER')),
    CONSTRAINT ck_recurring_transactions_frequency CHECK (frequency IN ('DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY', 'CUSTOM')),
    CONSTRAINT ck_recurring_transactions_interval CHECK (interval_count >= 1),
    CONSTRAINT ck_recurring_transactions_unit CHECK (interval_unit IS NULL OR interval_unit IN ('DAYS', 'WEEKS', 'MONTHS')),
    CONSTRAINT ck_recurring_transactions_custom CHECK ((frequency = 'CUSTOM') = (interval_unit IS NOT NULL)
        AND (frequency = 'CUSTOM' OR interval_count = 1)),
    CONSTRAINT ck_recurring_transactions_period CHECK (end_date IS NULL OR end_date >= start_date),
    CONSTRAINT ck_recurring_transactions_max CHECK (max_occurrences IS NULL OR max_occurrences >= 1),
    CONSTRAINT ck_recurring_transactions_target CHECK (
        (type = 'CARD_PURCHASE' AND card_id IS NOT NULL AND account_id IS NULL AND payment_method IS NULL)
        OR (type <> 'CARD_PURCHASE' AND account_id IS NOT NULL AND payment_method IS NOT NULL AND card_id IS NULL))
);

CREATE INDEX ix_recurring_transactions_user ON recurring_transactions (user_id);

-- Ocorrências puladas: não viram movimentação e saem dos compromissos. Uma linha por (recorrência, data).
CREATE TABLE recurrence_skips (
    id               UUID         NOT NULL,
    user_id          UUID         NOT NULL,
    recurrence_id    UUID         NOT NULL,
    occurrence_date  DATE         NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_recurrence_skips PRIMARY KEY (id),
    CONSTRAINT fk_recurrence_skips_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_recurrence_skips_recurrence_id_user FOREIGN KEY (recurrence_id, user_id)
        REFERENCES recurring_transactions (id, user_id),
    CONSTRAINT uq_recurrence_skips_recurrence_date UNIQUE (recurrence_id, occurrence_date)
);

CREATE INDEX ix_recurrence_skips_user ON recurrence_skips (user_id);

-- transactions.recurrence_id era uma coluna solta (V1.06): agora aponta para a recorrência (FK composta, mesmo usuário).
ALTER TABLE transactions
    ADD CONSTRAINT fk_transactions_recurrence_id_user FOREIGN KEY (recurrence_id, user_id)
        REFERENCES recurring_transactions (id, user_id);
