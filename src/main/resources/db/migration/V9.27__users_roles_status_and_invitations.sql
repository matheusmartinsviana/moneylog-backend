DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM users GROUP BY lower(email) HAVING count(*) > 1) THEN
        RAISE EXCEPTION 'V9.27: existem usuarios com e-mails iguais sem distincao de caixa; unifique-os antes de migrar';
    END IF;
END
$$;

ALTER TABLE users
    ADD COLUMN role          VARCHAR(16) NOT NULL DEFAULT 'MEMBER',
    ADD COLUMN status        VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN last_login_at TIMESTAMPTZ,
    ADD COLUMN disabled_at   TIMESTAMPTZ,
    ADD CONSTRAINT ck_users_role CHECK (role IN ('OWNER', 'MEMBER')),
    ADD CONSTRAINT ck_users_status CHECK (status IN ('ACTIVE', 'DISABLED')),
    ADD CONSTRAINT ck_users_disabled CHECK ((status = 'DISABLED') = (disabled_at IS NOT NULL));

UPDATE users
   SET role = 'OWNER'
 WHERE id = (SELECT id
               FROM users
              WHERE NOT EXISTS (SELECT 1 FROM users WHERE role = 'OWNER')
              ORDER BY (lower(email) = lower(btrim('${owner-email}'))) DESC, created_at, id
              LIMIT 1);

CREATE UNIQUE INDEX uq_users_single_owner ON users ((true)) WHERE role = 'OWNER';
CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email));

CREATE TABLE user_invitations (
    id               UUID         NOT NULL,
    email            VARCHAR(254) NOT NULL,
    token_hash       VARCHAR(64)  NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    invited_by       UUID         NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    expires_at       TIMESTAMPTZ  NOT NULL,
    last_sent_at     TIMESTAMPTZ  NOT NULL,
    delivery_status  VARCHAR(16)  NOT NULL,
    accepted_at      TIMESTAMPTZ,
    accepted_user_id UUID,
    CONSTRAINT pk_user_invitations PRIMARY KEY (id),
    CONSTRAINT uq_user_invitations_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_user_invitations_invited_by FOREIGN KEY (invited_by) REFERENCES users (id),
    CONSTRAINT fk_user_invitations_accepted_user FOREIGN KEY (accepted_user_id) REFERENCES users (id),
    CONSTRAINT ck_user_invitations_status CHECK (status IN ('PENDING', 'ACCEPTED', 'EXPIRED', 'REVOKED')),
    CONSTRAINT ck_user_invitations_delivery CHECK (delivery_status IN ('SENT', 'FAILED')),
    CONSTRAINT ck_user_invitations_expiry CHECK (expires_at > created_at),
    CONSTRAINT ck_user_invitations_accepted CHECK ((status = 'ACCEPTED') = (accepted_at IS NOT NULL AND accepted_user_id IS NOT NULL))
);

CREATE UNIQUE INDEX uq_user_invitations_pending_email ON user_invitations (lower(email)) WHERE status = 'PENDING';
CREATE INDEX ix_user_invitations_status_expires ON user_invitations (status, expires_at);
