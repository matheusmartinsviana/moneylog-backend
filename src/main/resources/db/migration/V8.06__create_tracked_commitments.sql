-- Compromissos acompanhados (ADR-014): empréstimo no nome de terceiro e projeto de longo prazo com cronograma próprio (imóvel).
-- Não há postings: nada aqui mexe em caixa, consumo, receita, patrimônio nem no total de dívidas. Aditiva.
CREATE TABLE tracked_commitments (
    id                 UUID          NOT NULL,
    user_id            UUID          NOT NULL,
    kind               VARCHAR(24)   NOT NULL,
    name               VARCHAR(100)  NOT NULL,
    counterparty       VARCHAR(100),
    notes              VARCHAR(1000),
    total_cents        BIGINT,
    informed_paid_cents BIGINT,
    installments_total INTEGER,
    installments_paid  INTEGER,
    next_due_date      DATE,
    created_at         TIMESTAMPTZ   NOT NULL,
    updated_at         TIMESTAMPTZ   NOT NULL,
    version            BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_tracked_commitments PRIMARY KEY (id),
    CONSTRAINT uq_tracked_commitments_id_user UNIQUE (id, user_id),
    CONSTRAINT fk_tracked_commitments_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_tracked_commitments_kind CHECK (kind IN ('TRACKED_THIRD_PARTY', 'LONG_TERM_PROJECT')),
    CONSTRAINT ck_tracked_commitments_amounts CHECK (
        (total_cents IS NULL OR total_cents > 0) AND (informed_paid_cents IS NULL OR informed_paid_cents >= 0)),
    CONSTRAINT ck_tracked_commitments_installments CHECK (
        (installments_total IS NULL AND installments_paid IS NULL)
        OR (installments_total >= 1 AND installments_paid >= 0 AND installments_paid <= installments_total))
);

CREATE INDEX ix_tracked_commitments_user ON tracked_commitments (user_id);

CREATE TABLE tracked_commitment_items (
    commitment_id UUID         NOT NULL,
    user_id       UUID         NOT NULL,
    position      INTEGER      NOT NULL,
    label         VARCHAR(100) NOT NULL,
    item_type     VARCHAR(16)  NOT NULL,
    due_date      DATE,
    amount_cents  BIGINT       NOT NULL,
    paid_on       DATE,
    CONSTRAINT pk_tracked_commitment_items PRIMARY KEY (commitment_id, position),
    CONSTRAINT fk_tracked_commitment_items_commitment FOREIGN KEY (commitment_id, user_id)
        REFERENCES tracked_commitments (id, user_id),
    CONSTRAINT ck_tracked_commitment_items_type CHECK (item_type IN ('DOWN_PAYMENT', 'INSTALLMENT', 'EXTRAORDINARY', 'FINANCING')),
    CONSTRAINT ck_tracked_commitment_items_amount CHECK (amount_cents > 0),
    CONSTRAINT ck_tracked_commitment_items_position CHECK (position >= 1)
);
