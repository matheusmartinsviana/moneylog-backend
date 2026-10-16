CREATE TABLE income_profiles (
    id                       UUID         NOT NULL,
    user_id                  UUID         NOT NULL,
    source                   VARCHAR(100) NOT NULL,
    gross_cents              BIGINT       NOT NULL,
    currency                 CHAR(3)      NOT NULL,
    dependents               SMALLINT     NOT NULL DEFAULT 0,
    advance_day              SMALLINT,
    advance_bps              INTEGER,
    balance_day              SMALLINT     NOT NULL,
    thirteenth_enabled       BOOLEAN      NOT NULL DEFAULT FALSE,
    thirteenth_first_month   SMALLINT     NOT NULL DEFAULT 11,
    archived                 BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at               TIMESTAMPTZ  NOT NULL,
    updated_at               TIMESTAMPTZ  NOT NULL,
    version                  BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_income_profiles PRIMARY KEY (id),
    CONSTRAINT uq_income_profiles_id_user UNIQUE (id, user_id),
    CONSTRAINT fk_income_profiles_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_income_profiles_gross CHECK (gross_cents > 0),
    CONSTRAINT ck_income_profiles_currency CHECK (currency = 'BRL'),
    CONSTRAINT ck_income_profiles_dependents CHECK (dependents BETWEEN 0 AND 20),
    CONSTRAINT ck_income_profiles_advance CHECK (
        (advance_day IS NULL AND advance_bps IS NULL)
        OR (advance_day BETWEEN 1 AND 28 AND advance_bps BETWEEN 0 AND 10000)),
    CONSTRAINT ck_income_profiles_balance_day CHECK (balance_day BETWEEN 1 AND 28),
    CONSTRAINT ck_income_profiles_thirteenth_month CHECK (thirteenth_first_month BETWEEN 1 AND 12)
);

CREATE INDEX ix_income_profiles_user ON income_profiles (user_id, archived, source);

CREATE TABLE income_profile_deductions (
    id          UUID         NOT NULL,
    user_id     UUID         NOT NULL,
    profile_id  UUID         NOT NULL,
    position    SMALLINT     NOT NULL,
    kind        VARCHAR(16)  NOT NULL,
    label       VARCHAR(100) NOT NULL,
    amount_cents BIGINT,
    percent_bps INTEGER,
    starts_on   DATE,
    ends_on     DATE,
    debt_id     UUID,
    CONSTRAINT pk_income_profile_deductions PRIMARY KEY (id),
    CONSTRAINT uq_income_profile_deductions_position UNIQUE (profile_id, position),
    CONSTRAINT fk_income_profile_deductions_profile FOREIGN KEY (profile_id, user_id) REFERENCES income_profiles (id, user_id),
    CONSTRAINT fk_income_profile_deductions_debt FOREIGN KEY (debt_id, user_id) REFERENCES debts (id, user_id),
    CONSTRAINT ck_income_profile_deductions_kind CHECK (kind IN ('TAX', 'BENEFIT', 'UNION', 'LOAN', 'OTHER')),
    CONSTRAINT ck_income_profile_deductions_value CHECK (
        (amount_cents IS NOT NULL AND amount_cents > 0 AND percent_bps IS NULL)
        OR (amount_cents IS NULL AND percent_bps BETWEEN 1 AND 10000)),
    CONSTRAINT ck_income_profile_deductions_period CHECK (starts_on IS NULL OR ends_on IS NULL OR ends_on >= starts_on),
    CONSTRAINT ck_income_profile_deductions_debt_kind CHECK (debt_id IS NULL OR kind = 'LOAN')
);

CREATE INDEX ix_income_profile_deductions_profile ON income_profile_deductions (user_id, profile_id);

CREATE TABLE income_profile_occurrences (
    id            UUID        NOT NULL,
    user_id       UUID        NOT NULL,
    profile_id    UUID        NOT NULL,
    competence    CHAR(7)     NOT NULL,
    kind          VARCHAR(20) NOT NULL,
    recurrence_id UUID        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_income_profile_occurrences PRIMARY KEY (id),
    CONSTRAINT uq_income_profile_occurrences_payment UNIQUE (profile_id, competence, kind),
    CONSTRAINT fk_income_profile_occurrences_profile FOREIGN KEY (profile_id, user_id) REFERENCES income_profiles (id, user_id),
    CONSTRAINT fk_income_profile_occurrences_recurrence FOREIGN KEY (recurrence_id, user_id) REFERENCES recurring_transactions (id, user_id),
    CONSTRAINT ck_income_profile_occurrences_kind CHECK (kind IN ('ADVANCE', 'BALANCE', 'THIRTEENTH_FIRST')),
    CONSTRAINT ck_income_profile_occurrences_competence CHECK (competence ~ '^[0-9]{4}-(0[1-9]|1[0-2])$')
);

CREATE INDEX ix_income_profile_occurrences_recurrence ON income_profile_occurrences (user_id, recurrence_id);

ALTER TABLE recurring_transactions DROP CONSTRAINT ck_recurring_income_competence_offset;
ALTER TABLE recurring_transactions ADD CONSTRAINT ck_recurring_income_competence_offset CHECK (
    income_competence_offset_months IN (-1, 0, 1) AND (income_competence_offset_months = 0 OR type = 'INCOME'));
