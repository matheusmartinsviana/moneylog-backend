CREATE TABLE recurrence_shares (
    id            UUID        NOT NULL,
    user_id       UUID        NOT NULL,
    recurrence_id UUID        NOT NULL,
    position      SMALLINT    NOT NULL,
    split_mode    VARCHAR(8)  NOT NULL,
    person_id     UUID,
    bps           INTEGER,
    amount_cents  BIGINT,
    created_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_recurrence_shares PRIMARY KEY (id),
    CONSTRAINT fk_recurrence_shares_recurrence FOREIGN KEY (recurrence_id, user_id)
        REFERENCES recurring_transactions (id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_recurrence_shares_person FOREIGN KEY (person_id, user_id) REFERENCES persons (id, user_id),
    CONSTRAINT uq_recurrence_shares_position UNIQUE (recurrence_id, position),
    CONSTRAINT ck_recurrence_shares_mode CHECK (split_mode IN ('BPS', 'EQUAL', 'AMOUNT')),
    CONSTRAINT ck_recurrence_shares_values CHECK (
        (split_mode = 'BPS' AND bps BETWEEN 1 AND 10000 AND amount_cents IS NULL)
        OR (split_mode = 'AMOUNT' AND bps IS NULL AND amount_cents > 0)
        OR (split_mode = 'EQUAL' AND bps IS NULL AND amount_cents IS NULL))
);

CREATE UNIQUE INDEX uq_recurrence_shares_person ON recurrence_shares (recurrence_id, person_id) WHERE person_id IS NOT NULL;
CREATE UNIQUE INDEX uq_recurrence_shares_owner ON recurrence_shares (recurrence_id) WHERE person_id IS NULL;
CREATE INDEX ix_recurrence_shares_person ON recurrence_shares (user_id, person_id) WHERE person_id IS NOT NULL;
