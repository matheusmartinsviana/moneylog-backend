ALTER TABLE credit_card_invoices ADD COLUMN document_amount_cents BIGINT;

ALTER TABLE credit_card_invoices ADD CONSTRAINT ck_credit_card_invoices_document_amount
    CHECK (document_amount_cents IS NULL OR document_amount_cents >= 0);
