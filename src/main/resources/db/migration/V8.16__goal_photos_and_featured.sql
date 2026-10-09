-- Metas de sonho com fotos (ADR-018), tudo aditivo.
-- 1) Meta em destaque da Home: no máximo uma por usuário (índice único parcial); só meta de guardar dinheiro e ativa.
ALTER TABLE goals ADD COLUMN featured BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE goals ADD CONSTRAINT ck_goals_featured_shape CHECK (NOT featured OR (kind = 'SAVINGS' AND archived = FALSE));
CREATE UNIQUE INDEX uq_goals_user_featured ON goals (user_id) WHERE featured;

-- 2) Metadados das fotos: nunca carregam bytes (a leitura de listas e de metas não toca a tabela de bytes).
--    position é a ordem de exibição (0 = capa); a unicidade é adiada para o fim da transação para permitir reordenar.
CREATE TABLE goal_photos (
    id          UUID         NOT NULL,
    user_id     UUID         NOT NULL,
    goal_id     UUID         NOT NULL,
    position    INTEGER      NOT NULL,
    width       INTEGER      NOT NULL,
    height      INTEGER      NOT NULL,
    size_bytes  BIGINT       NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_goal_photos PRIMARY KEY (id),
    CONSTRAINT uq_goal_photos_id_user UNIQUE (id, user_id),
    CONSTRAINT uq_goal_photos_position UNIQUE (goal_id, position) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_goal_photos_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_goal_photos_goal_id_user FOREIGN KEY (goal_id, user_id) REFERENCES goals (id, user_id) ON DELETE CASCADE,
    CONSTRAINT ck_goal_photos_position CHECK (position >= 0),
    CONSTRAINT ck_goal_photos_size CHECK (width > 0 AND height > 0 AND size_bytes > 0)
);
CREATE INDEX ix_goal_photos_user ON goal_photos (user_id);

-- 3) Bytes das versões processadas (exibição e miniatura, JPEG), em tabela separada. Entram no pg_dump; ficam atrás da porta PhotoStorage.
CREATE TABLE goal_photo_blobs (
    photo_id    UUID         NOT NULL,
    user_id     UUID         NOT NULL,
    display     BYTEA        NOT NULL,
    thumbnail   BYTEA        NOT NULL,
    CONSTRAINT pk_goal_photo_blobs PRIMARY KEY (photo_id),
    CONSTRAINT fk_goal_photo_blobs_photo_user FOREIGN KEY (photo_id, user_id) REFERENCES goal_photos (id, user_id) ON DELETE CASCADE
);
