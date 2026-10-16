CREATE TABLE purchase_groups (
    id                   UUID         NOT NULL,
    user_id              UUID         NOT NULL,
    name                 VARCHAR(100) NOT NULL,
    interest             BOOLEAN      NOT NULL DEFAULT FALSE,
    declared_total_cents BIGINT,
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    version              BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_purchase_groups PRIMARY KEY (id),
    CONSTRAINT uq_purchase_groups_id_user UNIQUE (id, user_id),
    CONSTRAINT fk_purchase_groups_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_purchase_groups_name CHECK (length(btrim(name)) BETWEEN 1 AND 100),
    CONSTRAINT ck_purchase_groups_declared_total CHECK (declared_total_cents IS NULL OR declared_total_cents >= 0)
);

CREATE INDEX ix_purchase_groups_user_name ON purchase_groups (user_id, lower(name), id);

CREATE TABLE purchase_group_members (
    group_id       UUID        NOT NULL,
    user_id        UUID        NOT NULL,
    transaction_id UUID        NOT NULL,
    position       INTEGER     NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_purchase_group_members PRIMARY KEY (group_id, transaction_id),
    CONSTRAINT uq_purchase_group_members_transaction UNIQUE (transaction_id),
    CONSTRAINT fk_purchase_group_members_group FOREIGN KEY (group_id, user_id) REFERENCES purchase_groups (id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_purchase_group_members_transaction FOREIGN KEY (transaction_id, user_id) REFERENCES transactions (id, user_id),
    CONSTRAINT ck_purchase_group_members_position CHECK (position >= 1)
);

CREATE INDEX ix_purchase_group_members_user_group ON purchase_group_members (user_id, group_id, position);
