-- Contas (D1 §2.2). O saldo NÃO é coluna: é Σ dos postings ativos do livro ACCOUNT (ledger_postings).
-- O saldo inicial fica aqui como dado de cadastro e também vira o par de postings ACCOUNT_OPENING.
CREATE TABLE accounts (
    id                    UUID         NOT NULL,
    user_id               UUID         NOT NULL,
    name                  VARCHAR(100) NOT NULL,
    type                  VARCHAR(16)  NOT NULL,
    institution           VARCHAR(100),
    opening_balance_cents BIGINT       NOT NULL,
    currency              CHAR(3)      NOT NULL,
    opening_date          DATE         NOT NULL,
    status                VARCHAR(16)  NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL,
    updated_at            TIMESTAMPTZ  NOT NULL,
    version               BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_accounts PRIMARY KEY (id),
    CONSTRAINT fk_accounts_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_accounts_type CHECK (type IN ('CHECKING', 'SAVINGS', 'WALLET', 'INVESTMENT')),
    CONSTRAINT ck_accounts_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_accounts_currency CHECK (currency = 'BRL'),
    CONSTRAINT ck_accounts_name_not_blank CHECK (btrim(name) <> '')
);

CREATE INDEX ix_accounts_user ON accounts (user_id);
