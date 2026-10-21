CREATE TABLE quick_entry_tokens (
    id                   UUID         NOT NULL,
    user_id              UUID         NOT NULL,
    account_id           UUID         NOT NULL,
    label                VARCHAR(60)  NOT NULL,
    prefix               VARCHAR(8)   NOT NULL,
    token_hash           VARCHAR(64)  NOT NULL,
    scope                VARCHAR(16)  NOT NULL DEFAULT 'QUICK_ENTRY',
    expires_at           TIMESTAMPTZ  NOT NULL,
    revoked_at           TIMESTAMPTZ,
    revoked_reason       VARCHAR(16),
    last_used_at         TIMESTAMPTZ,
    last_used_ip         VARCHAR(45),
    last_used_user_agent VARCHAR(120),
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    version              BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_quick_entry_tokens PRIMARY KEY (id),
    CONSTRAINT uq_quick_entry_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT uq_quick_entry_tokens_id_user UNIQUE (id, user_id),
    CONSTRAINT fk_quick_entry_tokens_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_quick_entry_tokens_account_id_user FOREIGN KEY (account_id, user_id) REFERENCES accounts (id, user_id),
    CONSTRAINT ck_quick_entry_tokens_scope CHECK (scope IN ('QUICK_ENTRY')),
    CONSTRAINT ck_quick_entry_tokens_revoked_reason CHECK (revoked_reason IN ('USER', 'USER_DISABLED')),
    CONSTRAINT ck_quick_entry_tokens_revoked CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL)),
    CONSTRAINT ck_quick_entry_tokens_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_quick_entry_tokens_hash_format CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_quick_entry_tokens_prefix_format CHECK (prefix ~ '^[A-Za-z0-9_-]{8}$'),
    CONSTRAINT ck_quick_entry_tokens_label CHECK (btrim(label) <> '')
);

CREATE INDEX ix_quick_entry_tokens_user ON quick_entry_tokens (user_id, created_at DESC);
CREATE INDEX ix_quick_entry_tokens_account ON quick_entry_tokens (account_id, user_id);

CREATE TABLE quick_entry_audit (
    transaction_id UUID        NOT NULL,
    token_id       UUID        NOT NULL,
    user_id        UUID        NOT NULL,
    ip             VARCHAR(45),
    created_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_quick_entry_audit PRIMARY KEY (transaction_id),
    CONSTRAINT fk_quick_entry_audit_transaction_id_user FOREIGN KEY (transaction_id, user_id) REFERENCES transactions (id, user_id),
    CONSTRAINT fk_quick_entry_audit_token_id_user FOREIGN KEY (token_id, user_id) REFERENCES quick_entry_tokens (id, user_id)
);

CREATE INDEX ix_quick_entry_audit_token ON quick_entry_audit (token_id, user_id, created_at DESC);
