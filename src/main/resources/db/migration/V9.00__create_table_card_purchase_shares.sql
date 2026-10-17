CREATE TABLE card_purchase_shares (
    id             UUID         NOT NULL,
    user_id        UUID         NOT NULL,
    transaction_id UUID         NOT NULL,
    position       SMALLINT     NOT NULL,
    split_mode     VARCHAR(8)   NOT NULL,
    person_id      UUID,
    bps            INTEGER,
    amount_cents   BIGINT,
    currency       CHAR(3)      NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_card_purchase_shares PRIMARY KEY (id),
    CONSTRAINT fk_card_purchase_shares_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_card_purchase_shares_transaction FOREIGN KEY (transaction_id, user_id) REFERENCES transactions (id, user_id),
    CONSTRAINT fk_card_purchase_shares_person FOREIGN KEY (person_id, user_id) REFERENCES persons (id, user_id),
    CONSTRAINT uq_card_purchase_shares_position UNIQUE (transaction_id, position),
    CONSTRAINT ck_card_purchase_shares_mode CHECK (split_mode IN ('BPS', 'EQUAL', 'AMOUNT')),
    CONSTRAINT ck_card_purchase_shares_currency CHECK (currency = 'BRL'),
    CONSTRAINT ck_card_purchase_shares_values CHECK (
        (split_mode = 'BPS' AND bps BETWEEN 1 AND 10000 AND amount_cents IS NULL)
        OR (split_mode = 'AMOUNT' AND bps IS NULL AND amount_cents > 0)
        OR (split_mode = 'EQUAL' AND bps IS NULL AND amount_cents IS NULL))
);

CREATE UNIQUE INDEX uq_card_purchase_shares_person ON card_purchase_shares (transaction_id, person_id) WHERE person_id IS NOT NULL;
CREATE UNIQUE INDEX uq_card_purchase_shares_owner ON card_purchase_shares (transaction_id) WHERE person_id IS NULL;
CREATE INDEX ix_card_purchase_shares_person ON card_purchase_shares (user_id, person_id) WHERE person_id IS NOT NULL;
CREATE INDEX ix_card_purchase_shares_transaction ON card_purchase_shares (user_id, transaction_id);
