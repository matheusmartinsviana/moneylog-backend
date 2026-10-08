-- Despesa de terceiro paga pelo usuário (ADR-014): marca uma compra no cartão (ou despesa em conta) como de uma pessoa (irmã, mãe...).
-- O gasto continua sendo consumo bruto do usuário até o reembolso; o reembolso recebido é um REFUND ligado à compra (refund_of), que
-- reduz o consumo na categoria original e nunca é receita. Esta tabela só identifica o responsável: não gera posting. Aditiva.
CREATE TABLE third_party_expenses (
    id             UUID         NOT NULL,
    user_id        UUID         NOT NULL,
    transaction_id UUID         NOT NULL,
    person_id      UUID         NOT NULL,
    note           VARCHAR(200),
    created_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_third_party_expenses PRIMARY KEY (id),
    CONSTRAINT uq_third_party_expenses_transaction UNIQUE (user_id, transaction_id),
    CONSTRAINT fk_third_party_expenses_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_third_party_expenses_transaction FOREIGN KEY (transaction_id, user_id) REFERENCES transactions (id, user_id),
    CONSTRAINT fk_third_party_expenses_person FOREIGN KEY (person_id, user_id) REFERENCES persons (id, user_id)
);

CREATE INDEX ix_third_party_expenses_person ON third_party_expenses (user_id, person_id);
