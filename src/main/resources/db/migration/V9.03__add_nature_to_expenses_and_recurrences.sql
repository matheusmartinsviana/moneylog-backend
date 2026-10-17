ALTER TABLE transactions ADD COLUMN nature VARCHAR(16);
UPDATE transactions SET nature = 'VARIABLE' WHERE type = 'EXPENSE';
ALTER TABLE transactions ADD CONSTRAINT ck_transactions_nature CHECK (
    nature IS NULL OR (type = 'EXPENSE' AND nature IN ('FIXED', 'VARIABLE')));

ALTER TABLE recurring_transactions ADD COLUMN nature VARCHAR(16);
UPDATE recurring_transactions SET nature = 'FIXED' WHERE type = 'EXPENSE';
ALTER TABLE recurring_transactions ADD CONSTRAINT ck_recurring_transactions_nature CHECK (
    nature IS NULL OR (type = 'EXPENSE' AND nature IN ('FIXED', 'VARIABLE')));
