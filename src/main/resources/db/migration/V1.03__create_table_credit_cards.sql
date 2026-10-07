-- Cartões de crédito (D1 §2.5). Nunca guarda número do cartão nem CVV (PR 00 §26).
-- Os dias de fechamento/vencimento NÃO ficam aqui: a única fonte de verdade é credit_card_billing_rules (M4),
-- porque a regra muda por mês (S-42) e as faturas FUTURE são recalculadas pela regra do mês delas. O "dia atual"
-- exibido é a regra mais recente (CreditCard.closingDay()/dueDay()).
CREATE TABLE credit_cards (
    id                          UUID         NOT NULL,
    user_id                     UUID         NOT NULL,
    name                        VARCHAR(100) NOT NULL,
    institution                 VARCHAR(100),
    limit_cents                 BIGINT       NOT NULL,
    currency                    CHAR(3)      NOT NULL,
    default_payment_account_id  UUID,
    status                      VARCHAR(16)  NOT NULL,
    created_at                  TIMESTAMPTZ  NOT NULL,
    updated_at                  TIMESTAMPTZ  NOT NULL,
    version                     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_credit_cards PRIMARY KEY (id),
    CONSTRAINT fk_credit_cards_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_credit_cards_default_account FOREIGN KEY (default_payment_account_id) REFERENCES accounts (id),
    -- alvo da FK composta das regras: a regra é sempre do mesmo usuário do cartão
    CONSTRAINT uq_credit_cards_id_user UNIQUE (id, user_id),
    CONSTRAINT ck_credit_cards_limit_positive CHECK (limit_cents > 0),
    CONSTRAINT ck_credit_cards_currency CHECK (currency = 'BRL'),
    CONSTRAINT ck_credit_cards_status CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT ck_credit_cards_name_not_blank CHECK (btrim(name) <> '')
);

CREATE INDEX ix_credit_cards_user ON credit_cards (user_id);

-- Regras de fechamento/vencimento por mês de início (S-42). A primeira regra do cartão vale também para os
-- meses anteriores a ela; cada mudança vale a partir do mês seguinte ao da fatura corrente. Todo cartão tem ao menos
-- uma regra (garantido pelo domínio e gravado na mesma transação do cartão). user_id é amarrado ao dono do cartão
-- pela FK composta (card_id, user_id).
CREATE TABLE credit_card_billing_rules (
    id              UUID        NOT NULL,
    user_id         UUID        NOT NULL,
    card_id         UUID        NOT NULL,
    effective_from  CHAR(7)     NOT NULL,
    closing_day     SMALLINT    NOT NULL,
    due_day         SMALLINT    NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_credit_card_billing_rules PRIMARY KEY (id),
    CONSTRAINT fk_credit_card_billing_rules_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_credit_card_billing_rules_card FOREIGN KEY (card_id, user_id) REFERENCES credit_cards (id, user_id),
    CONSTRAINT uq_credit_card_billing_rules_card_from UNIQUE (card_id, effective_from),
    CONSTRAINT ck_credit_card_billing_rules_from CHECK (effective_from ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    CONSTRAINT ck_credit_card_billing_rules_closing_day CHECK (closing_day BETWEEN 1 AND 31),
    CONSTRAINT ck_credit_card_billing_rules_due_day CHECK (due_day BETWEEN 1 AND 31)
);
