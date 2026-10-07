-- Meta "quitar todas as dívidas" (T243, ADR-013): Goal.kind aditivo (padrão SAVINGS, as metas existentes não mudam). A meta DEBT_PAYOFF
-- não tem reservas; baseline_cents/baseline_at são o total de dívidas fixado quando o usuário confirma "cadastrei tudo" e, depois
-- disso, target_cents = baseline_cents. Antes da confirmação target_cents = 0.
ALTER TABLE goals ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'SAVINGS';
ALTER TABLE goals ADD COLUMN baseline_cents BIGINT;
ALTER TABLE goals ADD COLUMN baseline_at TIMESTAMPTZ;
ALTER TABLE goals DROP CONSTRAINT ck_goals_target_positive;
ALTER TABLE goals ADD CONSTRAINT ck_goals_kind CHECK (kind IN ('SAVINGS', 'DEBT_PAYOFF'));
ALTER TABLE goals ADD CONSTRAINT ck_goals_target_positive CHECK (
    (kind = 'SAVINGS' AND target_cents > 0 AND baseline_cents IS NULL AND baseline_at IS NULL)
    OR (kind = 'DEBT_PAYOFF' AND target_cents >= 0 AND (baseline_cents IS NULL) = (baseline_at IS NULL)
        AND (baseline_cents IS NULL OR (baseline_cents > 0 AND target_cents = baseline_cents))));
-- uma meta DEBT_PAYOFF ativa (não arquivada) por usuário (a regra também é validada no caso de uso; o índice fecha a corrida)
CREATE UNIQUE INDEX uq_goals_user_debt_payoff_active ON goals (user_id) WHERE kind = 'DEBT_PAYOFF' AND archived = FALSE;
