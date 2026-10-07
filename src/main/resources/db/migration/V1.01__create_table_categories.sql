-- Categorias (D1 §2.3): EXPENSE ou INCOME; de sistema podem ser renomeadas/arquivadas, nunca apagadas.
CREATE TABLE categories (
    id          UUID         NOT NULL,
    user_id     UUID         NOT NULL,
    name        VARCHAR(60)  NOT NULL,
    kind        VARCHAR(16)  NOT NULL,
    system      BOOLEAN      NOT NULL DEFAULT FALSE,
    archived    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT pk_categories PRIMARY KEY (id),
    CONSTRAINT fk_categories_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_categories_kind CHECK (kind IN ('EXPENSE', 'INCOME')),
    CONSTRAINT ck_categories_name_not_blank CHECK (btrim(name) <> '')
);

CREATE INDEX ix_categories_user ON categories (user_id);
