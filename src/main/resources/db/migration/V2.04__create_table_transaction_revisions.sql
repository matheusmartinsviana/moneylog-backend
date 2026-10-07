-- Revisões de movimentação (D1 §2.4 regra 2, S-68, T27). Cada edição grava aqui o estado ANTERIOR (snapshot JSON) na
-- mesma transação que troca a movimentação e os lançamentos dela; nada é apagado.
--  - revision: versão da movimentação no momento do snapshot + 1 (a edição da versão 0 grava a revisão 1);
--    única por movimentação (uq_transaction_revisions_transaction_revision), o que também barra duas edições concorrentes.
--  - FK composta (S-76): a revisão é do mesmo usuário da movimentação.
CREATE TABLE transaction_revisions (
    id              UUID        NOT NULL,
    user_id         UUID        NOT NULL,
    transaction_id  UUID        NOT NULL,
    revision        INTEGER     NOT NULL,
    snapshot        JSONB       NOT NULL,
    edited_at       TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_transaction_revisions PRIMARY KEY (id),
    CONSTRAINT fk_transaction_revisions_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_transaction_revisions_transaction_user FOREIGN KEY (transaction_id, user_id)
        REFERENCES transactions (id, user_id),
    CONSTRAINT uq_transaction_revisions_transaction_revision UNIQUE (transaction_id, revision),
    CONSTRAINT ck_transaction_revisions_revision CHECK (revision >= 1)
);
