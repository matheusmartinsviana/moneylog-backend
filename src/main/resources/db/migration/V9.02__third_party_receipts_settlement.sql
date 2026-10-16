ALTER TABLE third_party_receipts ADD COLUMN account_id UUID;
ALTER TABLE third_party_receipts ADD COLUMN settlement VARCHAR(20) NOT NULL DEFAULT 'ALREADY_IN_BALANCE';
ALTER TABLE third_party_receipts ADD COLUMN ledgered BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE third_party_receipts ADD CONSTRAINT fk_third_party_receipts_account FOREIGN KEY (account_id, user_id) REFERENCES accounts (id, user_id);
ALTER TABLE third_party_receipts ADD CONSTRAINT ck_third_party_receipts_settlement CHECK (
    (settlement = 'ACCOUNT' AND account_id IS NOT NULL AND ledgered)
    OR (settlement = 'ALREADY_IN_BALANCE' AND account_id IS NULL));
CREATE INDEX ix_third_party_receipts_transaction ON third_party_receipts (user_id, transaction_id) WHERE transaction_id IS NOT NULL;
