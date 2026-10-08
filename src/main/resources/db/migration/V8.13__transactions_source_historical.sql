-- Fatura histórica (ADR-014): item de cartão sem categoria (CARD -X / EQUITY +X) numa fatura já fechada, para registrar o pagamento real dela
-- sem inventar as compras. Aditiva: as linhas existentes continuam válidas.
ALTER TABLE transactions DROP CONSTRAINT ck_transactions_source;
ALTER TABLE transactions ADD CONSTRAINT ck_transactions_source CHECK (source IN ('MANUAL', 'IMPORT', 'RECURRENCE', 'ONGOING', 'HISTORICAL'));
