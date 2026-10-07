-- Metas e reservas lógicas (D1 §2.9, T93, S-147 a S-150). A reserva NÃO é lançamento: não existe linha em transactions nem em
-- ledger_postings por causa dela (caixa, consumo, receita e patrimônio não mudam). O valor atual da meta é Σ das reservas
-- (reserva positiva, liberação negativa); ACHIEVED é derivado (current >= target) e só o arquivamento é gravado.
-- FKs compostas (id, user_id) como nas demais (S-76): o banco recusa meta ou reserva apontando para registro de outro usuário.
CREATE TABLE goals (
    id            UUID          NOT NULL,
    user_id       UUID          NOT NULL,
    name          VARCHAR(100)  NOT NULL,
    target_cents  BIGINT        NOT NULL,
    currency      CHAR(3)       NOT NULL,
    deadline      DATE,
    priority      VARCHAR(8)    NOT NULL,
    description   VARCHAR(500),
    archived      BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ   NOT NULL,
    updated_at    TIMESTAMPTZ   NOT NULL,
    version       BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_goals PRIMARY KEY (id),
    CONSTRAINT uq_goals_id_user UNIQUE (id, user_id),
    CONSTRAINT fk_goals_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_goals_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_goals_target_positive CHECK (target_cents > 0),
    CONSTRAINT ck_goals_currency CHECK (currency = 'BRL'),
    CONSTRAINT ck_goals_priority CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW'))
);

CREATE INDEX ix_goals_user ON goals (user_id);

CREATE TABLE goal_reservations (
    id            UUID          NOT NULL,
    user_id       UUID          NOT NULL,
    goal_id       UUID          NOT NULL,
    account_id    UUID          NOT NULL,
    amount_cents  BIGINT        NOT NULL,
    currency      CHAR(3)       NOT NULL,
    reserved_on   DATE          NOT NULL,
    note          VARCHAR(200),
    created_at    TIMESTAMPTZ   NOT NULL,
    updated_at    TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_goal_reservations PRIMARY KEY (id),
    CONSTRAINT fk_goal_reservations_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_goal_reservations_goal_id_user FOREIGN KEY (goal_id, user_id) REFERENCES goals (id, user_id),
    CONSTRAINT fk_goal_reservations_account_id_user FOREIGN KEY (account_id, user_id) REFERENCES accounts (id, user_id),
    CONSTRAINT ck_goal_reservations_amount_not_zero CHECK (amount_cents <> 0),
    CONSTRAINT ck_goal_reservations_currency CHECK (currency = 'BRL')
);

CREATE INDEX ix_goal_reservations_user ON goal_reservations (user_id);
CREATE INDEX ix_goal_reservations_goal ON goal_reservations (goal_id);
CREATE INDEX ix_goal_reservations_account ON goal_reservations (account_id);
