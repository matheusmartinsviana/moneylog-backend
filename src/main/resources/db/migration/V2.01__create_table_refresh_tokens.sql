-- Refresh tokens opacos e rotativos (ADR-003, S-83). Só o hash SHA-256 do valor é gravado (nunca o token).
-- Uma família agrupa a cadeia de rotações de um login; reuso fora da janela de tolerância revoga a família inteira.
CREATE TABLE refresh_tokens (
    id           UUID        NOT NULL,
    user_id      UUID        NOT NULL,
    family_id    UUID        NOT NULL,
    token_hash   CHAR(64)    NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    revoked_at   TIMESTAMPTZ,
    replaced_by  UUID,
    replaced_at  TIMESTAMPTZ,
    CONSTRAINT pk_refresh_tokens PRIMARY KEY (id),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uq_refresh_tokens_id_user UNIQUE (id, user_id),
    -- FK composta (S-76): o sucessor é do mesmo usuário
    CONSTRAINT fk_refresh_tokens_replaced_by_id_user FOREIGN KEY (replaced_by, user_id) REFERENCES refresh_tokens (id, user_id),
    CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_refresh_tokens_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_refresh_tokens_replacement CHECK ((replaced_by IS NULL) = (replaced_at IS NULL))
);

CREATE INDEX ix_refresh_tokens_family ON refresh_tokens (family_id);
CREATE INDEX ix_refresh_tokens_user ON refresh_tokens (user_id);
