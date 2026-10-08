-- Despesa de terceiro: parte reembolsável (padrão = valor cobrado) e recebimentos já refletidos nos saldos, sem movimento de conta (ADR-014). Aditiva.
ALTER TABLE third_party_expenses ADD COLUMN amount_cents BIGINT;
ALTER TABLE third_party_expenses ADD CONSTRAINT ck_third_party_expenses_amount CHECK (amount_cents IS NULL OR amount_cents > 0);

-- Recebimento de terceiro registrado SEM movimento de conta (o dinheiro já está no saldo observado). Pode ou não estar ligado a uma despesa.
CREATE TABLE third_party_receipts (
    id             UUID         NOT NULL,
    user_id        UUID         NOT NULL,
    person_id      UUID         NOT NULL,
    transaction_id UUID,
    amount_cents   BIGINT       NOT NULL,
    received_on    DATE,
    note           VARCHAR(200),
    created_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_third_party_receipts PRIMARY KEY (id),
    CONSTRAINT fk_third_party_receipts_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_third_party_receipts_person FOREIGN KEY (person_id, user_id) REFERENCES persons (id, user_id),
    CONSTRAINT fk_third_party_receipts_transaction FOREIGN KEY (transaction_id, user_id) REFERENCES transactions (id, user_id),
    CONSTRAINT ck_third_party_receipts_amount CHECK (amount_cents > 0)
);

CREATE INDEX ix_third_party_receipts_person ON third_party_receipts (user_id, person_id);
