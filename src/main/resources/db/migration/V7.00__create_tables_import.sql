-- Importação de extrato (Etapa 7, T124): lote, linhas, mapeamento de colunas salvo e regras de categorização aprendidas.
-- O arquivo bruto NUNCA é guardado: só o hash e as linhas lidas.
CREATE TABLE import_batches (
    id             UUID         NOT NULL,
    user_id        UUID         NOT NULL,
    account_id     UUID,
    card_id        UUID,
    format         VARCHAR(8)   NOT NULL,
    file_sha256    CHAR(64)     NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    warnings       VARCHAR(500) NOT NULL DEFAULT '',
    skipped_info   TEXT         NOT NULL DEFAULT '',
    applied_at     TIMESTAMPTZ,
    undone_at      TIMESTAMPTZ,
    undo_reason    VARCHAR(500),
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_import_batches PRIMARY KEY (id),
    CONSTRAINT fk_import_batches_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_import_batches_account_id_user FOREIGN KEY (account_id, user_id) REFERENCES accounts (id, user_id),
    CONSTRAINT fk_import_batches_card_id_user FOREIGN KEY (card_id, user_id) REFERENCES credit_cards (id, user_id),
    CONSTRAINT uq_import_batches_id_user UNIQUE (id, user_id),
    CONSTRAINT ck_import_batches_one_source CHECK ((account_id IS NULL) <> (card_id IS NULL)),
    CONSTRAINT ck_import_batches_format CHECK (format IN ('CSV', 'OFX')),
    CONSTRAINT ck_import_batches_status CHECK (status IN ('PENDING_REVIEW', 'APPLIED', 'UNDONE', 'PARTIALLY_UNDONE'))
);
CREATE INDEX ix_import_batches_user_created ON import_batches (user_id, created_at DESC);
CREATE INDEX ix_import_batches_user_sha ON import_batches (user_id, file_sha256);

CREATE TABLE import_lines (
    id                       UUID          NOT NULL,
    user_id                  UUID          NOT NULL,
    batch_id                 UUID          NOT NULL,
    position                 INTEGER       NOT NULL,
    source_id                UUID          NOT NULL,
    line_date                DATE          NOT NULL,
    amount_cents             BIGINT        NOT NULL,
    direction                VARCHAR(3)    NOT NULL,
    description              VARCHAR(500)  NOT NULL DEFAULT '',
    normalized_description   VARCHAR(500)  NOT NULL DEFAULT '',
    fit_id                   VARCHAR(100),
    dedup_key                CHAR(64)      NOT NULL,
    effective_dedup_key      CHAR(64)      NOT NULL,
    forced                   BOOLEAN       NOT NULL DEFAULT FALSE,
    status                   VARCHAR(20)   NOT NULL,
    flags                    VARCHAR(200)  NOT NULL DEFAULT '',
    matches                  TEXT          NOT NULL DEFAULT '',
    reject_reason            VARCHAR(200),
    category_id              UUID,
    method                   VARCHAR(16),
    description_override     VARCHAR(200),
    counterpart_account_id   UUID,
    learn                    BOOLEAN       NOT NULL DEFAULT FALSE,
    suggestion_category_id   UUID,
    suggestion_method        VARCHAR(16),
    suggestion_confidence    VARCHAR(8),
    transaction_id           UUID,
    CONSTRAINT pk_import_lines PRIMARY KEY (id),
    CONSTRAINT fk_import_lines_batch_user FOREIGN KEY (batch_id, user_id) REFERENCES import_batches (id, user_id) ON DELETE CASCADE,
    CONSTRAINT uq_import_lines_batch_position UNIQUE (batch_id, position),
    CONSTRAINT ck_import_lines_amount CHECK (amount_cents > 0),
    CONSTRAINT ck_import_lines_direction CHECK (direction IN ('IN', 'OUT')),
    CONSTRAINT ck_import_lines_status CHECK (status IN ('PENDING', 'ACCEPTED', 'DUPLICATE', 'POSSIBLE_DUPLICATE', 'REJECTED')),
    CONSTRAINT ck_import_lines_confidence CHECK (suggestion_confidence IS NULL OR suggestion_confidence IN ('HIGH', 'MEDIUM'))
);
CREATE INDEX ix_import_lines_batch_status ON import_lines (user_id, batch_id, status);
-- janela de pareamento de transferência: linhas ainda não aplicadas de outros lotes
CREATE INDEX ix_import_lines_user_date ON import_lines (user_id, line_date);

-- a movimentação importada aponta para o lote (a coluna existe desde V1.06)
ALTER TABLE transactions ADD CONSTRAINT fk_transactions_import_batch FOREIGN KEY (import_batch_id) REFERENCES import_batches (id);
CREATE INDEX ix_transactions_import_batch ON transactions (import_batch_id) WHERE import_batch_id IS NOT NULL;

-- mapeamento de colunas do CSV por conta/cartão (S-169)
CREATE TABLE import_mappings (
    user_id       UUID         NOT NULL,
    source_id     UUID         NOT NULL,
    date_col      INTEGER      NOT NULL,
    description_col INTEGER    NOT NULL,
    amount_col    INTEGER,
    debit_col     INTEGER,
    credit_col    INTEGER,
    invert        BOOLEAN      NOT NULL DEFAULT FALSE,
    has_header    BOOLEAN      NOT NULL DEFAULT TRUE,
    delimiter     CHAR(1),
    updated_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_import_mappings PRIMARY KEY (user_id, source_id),
    CONSTRAINT fk_import_mappings_user FOREIGN KEY (user_id) REFERENCES users (id)
);

-- regras aprendidas e confirmadas pelo usuário (S-172): descrição normalizada + direção -> categoria
CREATE TABLE categorization_rules (
    id                      UUID         NOT NULL,
    user_id                 UUID         NOT NULL,
    direction               VARCHAR(3)   NOT NULL,
    normalized_description  VARCHAR(500) NOT NULL,
    category_id             UUID         NOT NULL,
    method                  VARCHAR(16),
    created_at              TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_categorization_rules PRIMARY KEY (id),
    CONSTRAINT fk_categorization_rules_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_categorization_rules_category_id_user FOREIGN KEY (category_id, user_id) REFERENCES categories (id, user_id),
    CONSTRAINT uq_categorization_rules_key UNIQUE (user_id, direction, normalized_description),
    CONSTRAINT ck_categorization_rules_direction CHECK (direction IN ('IN', 'OUT'))
);
