-- Usuários. Na Etapa 1 só existe o dono (criado no primeiro boot a partir do ambiente, Etapa 2).
-- O id é gerado pela aplicação (sem default de banco). Toda tabela de negócio referencia users(id).
CREATE TABLE users (
    id          UUID         NOT NULL,
    email       VARCHAR(254) NOT NULL,
    time_zone   VARCHAR(64)  NOT NULL DEFAULT 'America/Sao_Paulo',
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uq_users_email UNIQUE (email)
);
