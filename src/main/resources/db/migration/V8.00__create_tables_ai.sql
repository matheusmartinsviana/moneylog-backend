-- IA (Etapa 8): prévias aprovadas, registro de cada análise e preferências. A IA nunca escreve no razão.
CREATE TABLE ai_previews (
    id               UUID         NOT NULL,
    user_id          UUID         NOT NULL,
    purpose          VARCHAR(30)  NOT NULL,
    period           VARCHAR(7),
    language         VARCHAR(2)   NOT NULL,
    -- exatamente o JSON que iria ao provedor (já minimizado e redigido) e o SHA-256 conferido no envio
    payload          TEXT         NOT NULL,
    payload_hash     CHAR(64)     NOT NULL,
    facts            TEXT         NOT NULL,
    counts           TEXT         NOT NULL DEFAULT '{}',
    redactions       TEXT         NOT NULL DEFAULT '[]',
    estimated_tokens INTEGER      NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    expires_at       TIMESTAMPTZ  NOT NULL,
    used_at          TIMESTAMPTZ,
    CONSTRAINT pk_ai_previews PRIMARY KEY (id),
    CONSTRAINT fk_ai_previews_user FOREIGN KEY (user_id) REFERENCES users (id)
);
CREATE INDEX ix_ai_previews_expires ON ai_previews (expires_at);

CREATE TABLE ai_analyses (
    id                UUID         NOT NULL,
    user_id           UUID         NOT NULL,
    preview_id        UUID,
    idempotency_key   UUID         NOT NULL,
    purpose           VARCHAR(30)  NOT NULL,
    period            VARCHAR(7),
    provider          VARCHAR(20)  NOT NULL,
    model             VARCHAR(80)  NOT NULL DEFAULT '',
    status            VARCHAR(24)  NOT NULL,
    -- apagado depois da retenção (AI_PAYLOAD_RETENTION_DAYS); o registro permanece. DENIED nunca tem payload.
    redacted_payload  TEXT,
    payload_hash      CHAR(64),
    input_tokens      INTEGER      NOT NULL DEFAULT 0,
    output_tokens     INTEGER      NOT NULL DEFAULT 0,
    duration_ms       BIGINT       NOT NULL DEFAULT 0,
    result_text       TEXT,
    caveat            TEXT,
    fact_refs         TEXT         NOT NULL DEFAULT '[]',
    failure_reason    VARCHAR(30),
    created_at        TIMESTAMPTZ  NOT NULL,
    payload_purged_at TIMESTAMPTZ,
    CONSTRAINT pk_ai_analyses PRIMARY KEY (id),
    CONSTRAINT fk_ai_analyses_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uq_ai_analyses_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT ck_ai_analyses_status CHECK (status IN ('COMPLETED', 'FAILED', 'DENIED', 'REJECTED_UNGROUNDED', 'BUDGET_EXCEEDED')),
    CONSTRAINT ck_ai_analyses_denied_no_payload CHECK (status <> 'DENIED' OR redacted_payload IS NULL)
);
CREATE INDEX ix_ai_analyses_user_created ON ai_analyses (user_id, created_at DESC);

CREATE TABLE ai_settings (
    user_id               UUID        NOT NULL,
    daily_message_enabled BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at            TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_ai_settings PRIMARY KEY (user_id),
    CONSTRAINT fk_ai_settings_user FOREIGN KEY (user_id) REFERENCES users (id)
);
