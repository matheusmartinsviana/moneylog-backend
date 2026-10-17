ALTER TABLE ledger_postings DROP CONSTRAINT ck_ledger_postings_source_type;
ALTER TABLE ledger_postings ADD CONSTRAINT ck_ledger_postings_source_type CHECK (
    source_type IN ('TRANSACTION', 'ACCOUNT_OPENING', 'DEBT_RECOGNITION', 'DEBT_CANCELLATION', 'THIRD_PARTY_RECEIPT', 'SHARE_PAYABLE'));
