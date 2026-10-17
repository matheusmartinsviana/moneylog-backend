ALTER TABLE ledger_postings DROP CONSTRAINT ck_ledger_postings_source_type;
ALTER TABLE ledger_postings ALTER COLUMN source_type TYPE VARCHAR(40);
ALTER TABLE ledger_postings ADD CONSTRAINT ck_ledger_postings_source_type CHECK (
    source_type IN ('TRANSACTION', 'ACCOUNT_OPENING', 'DEBT_RECOGNITION', 'DEBT_CANCELLATION', 'THIRD_PARTY_RECEIPT',
                    'THIRD_PARTY_RECEIPT_IN_BALANCE', 'THIRD_PARTY_REFUND_ADJUSTMENT', 'SHARE_PAYABLE'));

UPDATE ledger_postings SET source_type = 'THIRD_PARTY_REFUND_ADJUSTMENT'
WHERE source_type = 'THIRD_PARTY_RECEIPT'
  AND source_id IN (SELECT source_id FROM ledger_postings WHERE source_type = 'THIRD_PARTY_RECEIPT' AND book_type = 'EXPENSE');

UPDATE ledger_postings SET source_type = 'THIRD_PARTY_RECEIPT_IN_BALANCE'
WHERE source_type = 'THIRD_PARTY_RECEIPT'
  AND source_id IN (SELECT source_id FROM ledger_postings WHERE source_type = 'THIRD_PARTY_RECEIPT' AND book_type = 'EQUITY');
