-- Metas de medida e plano (ADR-016), tudo aditivo.
-- 1) Goal.kind = METRIC: acompanha uma medida do sistema (metric) até target a partir de um baseline capturado na criação
--    (baseline_cents/baseline_at, que podem ser zero ou negativos no METRIC); direction é derivada (DECREASE se target < baseline).
--    O nome passa a ser opcional (só METRIC pode ficar sem nome; o front mostra o rótulo gerado pela medida).
ALTER TABLE goals ADD COLUMN metric VARCHAR(24);
ALTER TABLE goals ADD COLUMN direction VARCHAR(8);
ALTER TABLE goals ADD COLUMN plan_note VARCHAR(500);
ALTER TABLE goals ALTER COLUMN name DROP NOT NULL;
ALTER TABLE goals DROP CONSTRAINT ck_goals_kind;
ALTER TABLE goals DROP CONSTRAINT ck_goals_target_positive;
ALTER TABLE goals ADD CONSTRAINT ck_goals_kind CHECK (kind IN ('SAVINGS', 'DEBT_PAYOFF', 'METRIC'));
ALTER TABLE goals ADD CONSTRAINT ck_goals_target_positive CHECK (
    (kind = 'SAVINGS' AND target_cents > 0 AND baseline_cents IS NULL AND baseline_at IS NULL)
    OR (kind = 'DEBT_PAYOFF' AND target_cents >= 0 AND (baseline_cents IS NULL) = (baseline_at IS NULL)
        AND (baseline_cents IS NULL OR (baseline_cents > 0 AND target_cents = baseline_cents)))
    OR (kind = 'METRIC' AND target_cents >= 0 AND baseline_cents IS NOT NULL AND baseline_at IS NOT NULL
        AND target_cents <> baseline_cents));
ALTER TABLE goals ADD CONSTRAINT ck_goals_metric_shape CHECK (
    (kind = 'METRIC'
        AND metric IN ('TOTAL_DEBT', 'CARD_DEBT', 'PEOPLE_DEBT', 'OTHER_DEBT', 'ACCOUNTS_BALANCE', 'AVAILABLE_BALANCE', 'FIXED_COSTS_MONTHLY')
        AND ((direction = 'DECREASE' AND target_cents < baseline_cents) OR (direction = 'INCREASE' AND target_cents > baseline_cents)))
    OR (kind <> 'METRIC' AND metric IS NULL AND direction IS NULL AND name IS NOT NULL));
-- sem duplicata exata ativa: uma meta METRIC por combinação medida + alvo (também validado no caso de uso, que responde 409)
CREATE UNIQUE INDEX uq_goals_user_metric_target_active ON goals (user_id, metric, target_cents) WHERE kind = 'METRIC' AND archived = FALSE;

-- 2) Plano declarado: ações da meta e os check-ins por período (MONTHLY YYYY-MM, WEEKLY YYYY-Www, ONE_TIME ONCE). Não move dinheiro.
CREATE TABLE goal_actions (
    id          UUID         NOT NULL,
    user_id     UUID         NOT NULL,
    goal_id     UUID         NOT NULL,
    position    INTEGER      NOT NULL,
    title       VARCHAR(100) NOT NULL,
    frequency   VARCHAR(8)   NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_goal_actions PRIMARY KEY (id),
    CONSTRAINT uq_goal_actions_id_user UNIQUE (id, user_id),
    CONSTRAINT fk_goal_actions_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_goal_actions_goal_id_user FOREIGN KEY (goal_id, user_id) REFERENCES goals (id, user_id),
    CONSTRAINT ck_goal_actions_title_not_blank CHECK (btrim(title) <> ''),
    CONSTRAINT ck_goal_actions_frequency CHECK (frequency IN ('MONTHLY', 'WEEKLY', 'ONE_TIME'))
);
CREATE INDEX ix_goal_actions_user ON goal_actions (user_id);
CREATE INDEX ix_goal_actions_goal ON goal_actions (goal_id);

CREATE TABLE goal_action_checkins (
    id          UUID         NOT NULL,
    user_id     UUID         NOT NULL,
    action_id   UUID         NOT NULL,
    period      VARCHAR(8)   NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_goal_action_checkins PRIMARY KEY (id),
    CONSTRAINT uq_goal_action_checkins_period UNIQUE (action_id, period),
    CONSTRAINT fk_goal_action_checkins_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_goal_action_checkins_action_id_user FOREIGN KEY (action_id, user_id) REFERENCES goal_actions (id, user_id)
);
CREATE INDEX ix_goal_action_checkins_user ON goal_action_checkins (user_id);

-- 3) Instantâneo mensal da medida (METRIC e DEBT_PAYOFF): um por meta e mês (reference_month = dia 1), atualizado até o mês fechar.
--    Não se inventa histórico: meses sem linha são "sem dado".
CREATE TABLE goal_metric_snapshots (
    id               UUID         NOT NULL,
    user_id          UUID         NOT NULL,
    goal_id          UUID         NOT NULL,
    reference_month  DATE         NOT NULL,
    value_cents      BIGINT       NOT NULL,
    currency         CHAR(3)      NOT NULL,
    captured_at      TIMESTAMPTZ  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_goal_metric_snapshots PRIMARY KEY (id),
    CONSTRAINT uq_goal_metric_snapshots_goal_month UNIQUE (goal_id, reference_month),
    CONSTRAINT fk_goal_metric_snapshots_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_goal_metric_snapshots_goal_id_user FOREIGN KEY (goal_id, user_id) REFERENCES goals (id, user_id),
    CONSTRAINT ck_goal_metric_snapshots_month_first CHECK (EXTRACT(DAY FROM reference_month) = 1),
    CONSTRAINT ck_goal_metric_snapshots_currency CHECK (currency = 'BRL')
);
CREATE INDEX ix_goal_metric_snapshots_user ON goal_metric_snapshots (user_id);
