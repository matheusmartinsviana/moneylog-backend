CREATE TABLE assets (
    id                    UUID         NOT NULL,
    user_id               UUID         NOT NULL,
    kind                  VARCHAR(8)   NOT NULL,
    name                  VARCHAR(100) NOT NULL,
    employer              VARCHAR(100),
    balance_cents         BIGINT       NOT NULL,
    balance_date          DATE         NOT NULL,
    currency              CHAR(3)      NOT NULL,
    monthly_deposit_cents BIGINT,
    availability          VARCHAR(10)  NOT NULL,
    counts_as_reserve     BOOLEAN      NOT NULL DEFAULT FALSE,
    archived              BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    version               BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_assets PRIMARY KEY (id),
    CONSTRAINT uq_assets_id_user UNIQUE (id, user_id),
    CONSTRAINT fk_assets_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_assets_kind CHECK (kind IN ('FGTS', 'OTHER')),
    CONSTRAINT ck_assets_name CHECK (length(btrim(name)) BETWEEN 1 AND 100),
    CONSTRAINT ck_assets_balance CHECK (balance_cents >= 0),
    CONSTRAINT ck_assets_currency CHECK (currency = 'BRL'),
    CONSTRAINT ck_assets_monthly_deposit CHECK (monthly_deposit_cents IS NULL OR monthly_deposit_cents >= 0),
    CONSTRAINT ck_assets_availability CHECK (availability IN ('RESERVED', 'AVAILABLE')),
    CONSTRAINT ck_assets_fgts_reserved CHECK (kind <> 'FGTS' OR availability = 'RESERVED'),
    CONSTRAINT ck_assets_reserve_available CHECK (NOT counts_as_reserve OR availability = 'AVAILABLE')
);

CREATE INDEX ix_assets_user_archived_name ON assets (user_id, archived, lower(name), id);

CREATE TABLE asset_balances (
    id            UUID        NOT NULL,
    user_id       UUID        NOT NULL,
    asset_id      UUID        NOT NULL,
    balance_cents BIGINT      NOT NULL,
    currency      CHAR(3)     NOT NULL,
    balance_date  DATE        NOT NULL,
    recorded_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_asset_balances PRIMARY KEY (id),
    CONSTRAINT fk_asset_balances_asset FOREIGN KEY (asset_id, user_id) REFERENCES assets (id, user_id),
    CONSTRAINT ck_asset_balances_balance CHECK (balance_cents >= 0),
    CONSTRAINT ck_asset_balances_currency CHECK (currency = 'BRL')
);

CREATE INDEX ix_asset_balances_asset ON asset_balances (user_id, asset_id, recorded_at DESC, id);
