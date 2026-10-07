-- Idempotência de criações (S-72, S-87, T26b). A chave é gravada na MESMA transação da criação do recurso: se a
-- criação falha (4xx/5xx), a chave sai no rollback (erros não são memorizados); duas requisições concorrentes com a
-- mesma chave serializam no índice único e a segunda lê o recurso depois do commit da primeira.
--  - scope: a rota que usa a chave ('POST /v1/transactions'); a tabela serve também a outras rotas (ex.: pagamento de fatura).
--  - request_hash: SHA-256 (hex) do comando canônico (não dos bytes do corpo).
--  - resource_id: id do recurso criado; polimórfico por scope, por isso sem FK.
--  - Chave vencida (mais de 24 h) é sobrescrita pela próxima criação (sem job de limpeza nesta etapa).
CREATE TABLE idempotency_keys (
    id               UUID         NOT NULL,
    user_id          UUID         NOT NULL,
    scope            VARCHAR(100) NOT NULL,
    idempotency_key  UUID         NOT NULL,
    request_hash     CHAR(64)     NOT NULL,
    resource_id      UUID         NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_idempotency_keys PRIMARY KEY (id),
    CONSTRAINT fk_idempotency_keys_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uq_idempotency_keys_user_scope_key UNIQUE (user_id, scope, idempotency_key)
);
