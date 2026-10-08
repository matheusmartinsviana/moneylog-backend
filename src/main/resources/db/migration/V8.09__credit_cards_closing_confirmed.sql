-- Fechamento do cartão confirmado por documento (ADR-014). FALSE = o dia numérico é só parâmetro técnico provisório, nunca um fato.
-- Aditiva: os cartões existentes ficam confirmados (comportamento atual).
ALTER TABLE credit_cards ADD COLUMN closing_confirmed BOOLEAN NOT NULL DEFAULT TRUE;
