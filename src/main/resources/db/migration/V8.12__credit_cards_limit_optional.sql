-- Limite do cartão opcional (ADR-014): o dono define depois. Aditiva: os cartões existentes mantêm o limite. Limite informado continua > 0.
ALTER TABLE credit_cards ALTER COLUMN limit_cents DROP NOT NULL;
ALTER TABLE credit_cards DROP CONSTRAINT ck_credit_cards_limit_positive;
ALTER TABLE credit_cards ADD CONSTRAINT ck_credit_cards_limit_positive CHECK (limit_cents IS NULL OR limit_cents > 0);
