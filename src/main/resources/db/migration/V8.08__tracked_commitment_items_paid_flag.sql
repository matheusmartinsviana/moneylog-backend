-- Item do cronograma pago sem data/comprovante (pagamento informado pelo usuário): a marca "pago" é independente da data do pagamento.
ALTER TABLE tracked_commitment_items ADD COLUMN paid BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE tracked_commitment_items SET paid = TRUE WHERE paid_on IS NOT NULL;
ALTER TABLE tracked_commitment_items ADD CONSTRAINT ck_tracked_commitment_items_paid CHECK (paid OR paid_on IS NULL);
