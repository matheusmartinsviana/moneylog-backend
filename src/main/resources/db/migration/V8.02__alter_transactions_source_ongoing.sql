-- Visão total de dívidas (T241, ADR-013): compra parcelada em andamento é uma CARD_PURCHASE com origem ONGOING (só as parcelas restantes,
-- sem consumo retroativo). Aditiva: as linhas existentes (MANUAL, IMPORT, RECURRENCE) continuam válidas.
ALTER TABLE transactions DROP CONSTRAINT ck_transactions_source;
ALTER TABLE transactions ADD CONSTRAINT ck_transactions_source CHECK (source IN ('MANUAL', 'IMPORT', 'RECURRENCE', 'ONGOING'));
