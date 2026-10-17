ALTER TABLE tracked_commitment_items ADD COLUMN co_buyer_person_id UUID;

CREATE TABLE tracked_commitment_item_shares (
    commitment_id UUID    NOT NULL,
    user_id       UUID    NOT NULL,
    position      INTEGER NOT NULL,
    share_order   INTEGER NOT NULL,
    person_id     UUID,
    amount_cents  BIGINT  NOT NULL,
    CONSTRAINT pk_tracked_commitment_item_shares PRIMARY KEY (commitment_id, position, share_order),
    CONSTRAINT fk_tracked_commitment_item_shares_item FOREIGN KEY (commitment_id, position)
        REFERENCES tracked_commitment_items (commitment_id, position) ON DELETE CASCADE,
    CONSTRAINT fk_tracked_commitment_item_shares_person FOREIGN KEY (person_id, user_id) REFERENCES persons (id, user_id),
    CONSTRAINT ck_tracked_commitment_item_shares_amount CHECK (amount_cents > 0)
);

CREATE UNIQUE INDEX uq_tracked_commitment_item_shares_person
    ON tracked_commitment_item_shares (commitment_id, position, person_id) WHERE person_id IS NOT NULL;
CREATE UNIQUE INDEX uq_tracked_commitment_item_shares_owner
    ON tracked_commitment_item_shares (commitment_id, position) WHERE person_id IS NULL;
CREATE INDEX ix_tracked_commitment_item_shares_person
    ON tracked_commitment_item_shares (user_id, person_id) WHERE person_id IS NOT NULL;

ALTER TABLE tracked_commitments ADD COLUMN late_fee_bps INTEGER;
ALTER TABLE tracked_commitments ADD COLUMN monthly_interest_bps INTEGER;
ALTER TABLE tracked_commitments ADD COLUMN termination_retention_bps INTEGER;
ALTER TABLE tracked_commitments ADD COLUMN clauses_notes VARCHAR(1000);
ALTER TABLE tracked_commitments ADD CONSTRAINT ck_tracked_commitments_clauses CHECK (
    (late_fee_bps IS NULL AND monthly_interest_bps IS NULL AND termination_retention_bps IS NULL AND clauses_notes IS NULL)
    OR (late_fee_bps BETWEEN 0 AND 10000 AND monthly_interest_bps BETWEEN 0 AND 10000 AND termination_retention_bps BETWEEN 0 AND 10000));
