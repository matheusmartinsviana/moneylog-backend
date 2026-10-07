-- "Outras dívidas" (T244, P1, ADR-013): dívida PAYABLE com credor livre (banco, loja, financiamento), sem Person. Aditiva: as dívidas
-- existentes têm person_id e creditor_name nulo. Exatamente um dos dois existe; credor livre só em PAYABLE.
ALTER TABLE debts ALTER COLUMN person_id DROP NOT NULL;
ALTER TABLE debts ADD COLUMN creditor_name VARCHAR(100);
ALTER TABLE debts ADD CONSTRAINT ck_debts_creditor CHECK (
    (person_id IS NOT NULL AND creditor_name IS NULL)
    OR (person_id IS NULL AND creditor_name IS NOT NULL AND btrim(creditor_name) <> '' AND direction = 'PAYABLE'));
