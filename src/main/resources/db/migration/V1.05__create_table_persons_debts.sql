-- Pessoas e dívidas (D1 §2.6). OPEN/PARTIALLY_SETTLED/SETTLED são derivados das quitações ativas (S-41):
-- só o encerramento (closure = CANCELED por perdão ou VOIDED por estorno) é gravado.
CREATE TABLE persons (
    id          UUID         NOT NULL,
    user_id     UUID         NOT NULL,
    name        VARCHAR(100) NOT NULL,
    notes       TEXT,
    archived    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_persons PRIMARY KEY (id),
    CONSTRAINT fk_persons_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_persons_name_not_blank CHECK (btrim(name) <> '')
);

CREATE INDEX ix_persons_user ON persons (user_id);

CREATE TABLE debts (
    id                     UUID         NOT NULL,
    user_id                UUID         NOT NULL,
    person_id              UUID         NOT NULL,
    direction              VARCHAR(16)  NOT NULL,
    origin                 VARCHAR(20)  NOT NULL,
    principal_cents        BIGINT       NOT NULL,
    currency               CHAR(3)      NOT NULL,
    description            VARCHAR(200),
    debt_date              DATE         NOT NULL,
    expected_date          DATE,
    -- FK para transactions criada em V1.06 (DEFERRABLE: dívida e origem são gravadas na mesma transação).
    origin_transaction_id  UUID,
    closure                VARCHAR(16),
    closure_date           DATE,
    closure_reason         VARCHAR(500),
    created_at             TIMESTAMPTZ  NOT NULL,
    updated_at             TIMESTAMPTZ  NOT NULL,
    version                BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_debts PRIMARY KEY (id),
    CONSTRAINT fk_debts_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_debts_person FOREIGN KEY (person_id) REFERENCES persons (id),
    CONSTRAINT ck_debts_direction CHECK (direction IN ('RECEIVABLE', 'PAYABLE')),
    CONSTRAINT ck_debts_origin CHECK (origin IN ('WITH_MOVEMENT', 'WITHOUT_MOVEMENT')),
    CONSTRAINT ck_debts_origin_transaction CHECK (
        (origin = 'WITH_MOVEMENT' AND origin_transaction_id IS NOT NULL)
        OR (origin = 'WITHOUT_MOVEMENT' AND origin_transaction_id IS NULL)),
    CONSTRAINT ck_debts_principal_positive CHECK (principal_cents > 0),
    CONSTRAINT ck_debts_currency CHECK (currency = 'BRL'),
    CONSTRAINT ck_debts_closure CHECK (closure IS NULL OR closure IN ('CANCELED', 'VOIDED')),
    CONSTRAINT ck_debts_closure_fields CHECK (
        (closure IS NULL AND closure_date IS NULL AND closure_reason IS NULL)
        OR (closure IS NOT NULL AND closure_date IS NOT NULL AND closure_reason IS NOT NULL))
);

CREATE UNIQUE INDEX uq_debts_origin_transaction ON debts (origin_transaction_id) WHERE origin_transaction_id IS NOT NULL;
CREATE INDEX ix_debts_user_expected ON debts (user_id, expected_date);
