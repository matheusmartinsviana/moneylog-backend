CREATE TABLE third_party_mark_migrations (
    id                    UUID         NOT NULL,
    user_id               UUID         NOT NULL,
    transaction_id        UUID         NOT NULL,
    person_id             UUID         NOT NULL,
    mark_id               UUID         NOT NULL,
    mark_note             VARCHAR(200),
    mark_amount_cents     BIGINT,
    split_mode            VARCHAR(8)   NOT NULL,
    third_cents           BIGINT       NOT NULL,
    refunded_cents        BIGINT       NOT NULL,
    legacy_received_cents BIGINT       NOT NULL,
    report_hash           CHAR(64)     NOT NULL,
    migrated_at           TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_third_party_mark_migrations PRIMARY KEY (id),
    CONSTRAINT uq_third_party_mark_migrations_transaction UNIQUE (user_id, transaction_id),
    CONSTRAINT fk_third_party_mark_migrations_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_third_party_mark_migrations_transaction FOREIGN KEY (transaction_id, user_id) REFERENCES transactions (id, user_id),
    CONSTRAINT fk_third_party_mark_migrations_person FOREIGN KEY (person_id, user_id) REFERENCES persons (id, user_id),
    CONSTRAINT ck_third_party_mark_migrations_mode CHECK (split_mode IN ('BPS', 'AMOUNT')),
    CONSTRAINT ck_third_party_mark_migrations_values CHECK (third_cents > 0 AND refunded_cents >= 0 AND legacy_received_cents >= 0)
);

CREATE INDEX ix_third_party_mark_migrations_hash ON third_party_mark_migrations (user_id, report_hash);
