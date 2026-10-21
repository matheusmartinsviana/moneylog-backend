ALTER TABLE transactions DROP CONSTRAINT ck_transactions_source;
ALTER TABLE transactions ADD CONSTRAINT ck_transactions_source CHECK (source IN ('MANUAL', 'IMPORT', 'RECURRENCE', 'ONGOING', 'HISTORICAL', 'QUICK_SHORTCUT'));
