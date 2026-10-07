-- Vault de notas Markdown (Etapa 6, T108, ADR-009, D1 §5, S-157 a S-166). As notas moram no Postgres; a compatibilidade com o Obsidian é
-- por exportação/importação de zip. NADA aqui toca razão, saldo ou qualquer cálculo financeiro: as notas só apontam para as entidades.
-- FKs compostas (id, user_id) como nas demais (S-76): o banco recusa nota, tag ou vínculo apontando para registro de outro usuário.
CREATE TABLE notes (
    id          UUID          NOT NULL,
    user_id     UUID          NOT NULL,
    path        VARCHAR(255)  NOT NULL,
    -- chave de unicidade: o caminho sem diferenciar maiúsculas (calculada pela aplicação, igual em qualquer locale do banco)
    path_key    VARCHAR(512)  NOT NULL,
    title       VARCHAR(255)  NOT NULL,
    -- o texto como foi escrito (frontmatter incluído, exceto a chave reservada moneylog); até 200 KB
    body        TEXT          NOT NULL,
    -- lixeira (S-24, S-161): preenchido = na lixeira
    deleted_at  TIMESTAMPTZ,
    created_at  TIMESTAMPTZ   NOT NULL,
    updated_at  TIMESTAMPTZ   NOT NULL,
    version     BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT pk_notes PRIMARY KEY (id),
    CONSTRAINT uq_notes_id_user UNIQUE (id, user_id),
    CONSTRAINT fk_notes_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_notes_path_not_blank CHECK (btrim(path) <> ''),
    CONSTRAINT ck_notes_title_not_blank CHECK (btrim(title) <> ''),
    CONSTRAINT ck_notes_body_size CHECK (octet_length(body) <= 204800)
);

-- o caminho é único por usuário só entre as notas fora da lixeira: a lixeira não impede recriar a nota
CREATE UNIQUE INDEX uq_notes_user_path_active ON notes (user_id, path_key) WHERE deleted_at IS NULL;
CREATE INDEX ix_notes_user_updated ON notes (user_id, updated_at DESC);
CREATE INDEX ix_notes_user_deleted ON notes (user_id) WHERE deleted_at IS NOT NULL;

CREATE TABLE note_tags (
    id          UUID         NOT NULL,
    user_id     UUID         NOT NULL,
    note_id     UUID         NOT NULL,
    tag         VARCHAR(64)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_note_tags PRIMARY KEY (id),
    CONSTRAINT uq_note_tags_note_tag UNIQUE (note_id, tag),
    CONSTRAINT fk_note_tags_note_id_user FOREIGN KEY (note_id, user_id) REFERENCES notes (id, user_id) ON DELETE CASCADE,
    CONSTRAINT ck_note_tags_not_blank CHECK (btrim(tag) <> '')
);

CREATE INDEX ix_note_tags_user_tag ON note_tags (user_id, tag);

-- Vínculos (S-160). Um alvo tipado por coluna, cada uma com FK composta: nunca há dupla referência nem referência órfã.
--   WIKILINK : target_type = 'NOTE', target_text = o alvo como escrito; target_note_id = a nota resolvida (NULL = pendente).
--   EXPLICIT : exatamente UMA referência entre as colunas target_*_id, target_key (resumo mensal) e target_text (alvo que não existe,
--              vindo de importação = pendente; guarda o valor original para a exportação devolvê-lo).
-- target_note_id é DEFERRABLE: a importação cria as notas em qualquer ordem e a FK é conferida no commit.
CREATE TABLE note_links (
    id                    UUID          NOT NULL,
    user_id               UUID          NOT NULL,
    note_id               UUID          NOT NULL,
    position              INTEGER       NOT NULL,
    origin                VARCHAR(8)    NOT NULL,
    target_type           VARCHAR(16)   NOT NULL,
    target_note_id        UUID,
    target_transaction_id UUID,
    target_account_id     UUID,
    target_card_id        UUID,
    target_invoice_id     UUID,
    target_person_id      UUID,
    target_debt_id        UUID,
    target_goal_id        UUID,
    target_key            CHAR(7),
    target_text           VARCHAR(255),
    alias                 VARCHAR(255),
    section               VARCHAR(255),
    embed                 BOOLEAN       NOT NULL DEFAULT FALSE,
    context               VARCHAR(200),
    created_at            TIMESTAMPTZ   NOT NULL,
    updated_at            TIMESTAMPTZ   NOT NULL,
    CONSTRAINT pk_note_links PRIMARY KEY (id),
    CONSTRAINT fk_note_links_note_id_user FOREIGN KEY (note_id, user_id) REFERENCES notes (id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_note_links_target_note_id_user FOREIGN KEY (target_note_id, user_id) REFERENCES notes (id, user_id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_note_links_target_transaction_id_user FOREIGN KEY (target_transaction_id, user_id) REFERENCES transactions (id, user_id),
    CONSTRAINT fk_note_links_target_account_id_user FOREIGN KEY (target_account_id, user_id) REFERENCES accounts (id, user_id),
    CONSTRAINT fk_note_links_target_card_id_user FOREIGN KEY (target_card_id, user_id) REFERENCES credit_cards (id, user_id),
    -- uma fatura vazia pode ser removida quando o calendário do cartão muda (ADR-006): o vínculo some com ela
    CONSTRAINT fk_note_links_target_invoice_id_user FOREIGN KEY (target_invoice_id, user_id) REFERENCES credit_card_invoices (id, user_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_note_links_target_person_id_user FOREIGN KEY (target_person_id, user_id) REFERENCES persons (id, user_id),
    CONSTRAINT fk_note_links_target_debt_id_user FOREIGN KEY (target_debt_id, user_id) REFERENCES debts (id, user_id),
    CONSTRAINT fk_note_links_target_goal_id_user FOREIGN KEY (target_goal_id, user_id) REFERENCES goals (id, user_id),
    CONSTRAINT ck_note_links_origin CHECK (origin IN ('WIKILINK', 'EXPLICIT')),
    CONSTRAINT ck_note_links_type CHECK (target_type IN ('TRANSACTION', 'ACCOUNT', 'CARD', 'INVOICE', 'PERSON', 'DEBT', 'GOAL',
        'MONTHLY_SUMMARY', 'NOTE')),
    -- cada coluna só vale para o seu tipo
    CONSTRAINT ck_note_links_column_matches_type CHECK (
        (target_note_id IS NULL OR target_type = 'NOTE')
        AND (target_transaction_id IS NULL OR target_type = 'TRANSACTION')
        AND (target_account_id IS NULL OR target_type = 'ACCOUNT')
        AND (target_card_id IS NULL OR target_type = 'CARD')
        AND (target_invoice_id IS NULL OR target_type = 'INVOICE')
        AND (target_person_id IS NULL OR target_type = 'PERSON')
        AND (target_debt_id IS NULL OR target_type = 'DEBT')
        AND (target_goal_id IS NULL OR target_type = 'GOAL')
        AND (target_key IS NULL OR target_type = 'MONTHLY_SUMMARY')),
    CONSTRAINT ck_note_links_key_format CHECK (target_key IS NULL OR target_key ~ '^(20[0-9]{2}|2100)-(0[1-9]|1[0-2])$'),
    CONSTRAINT ck_note_links_wikilink CHECK (origin <> 'WIKILINK' OR (target_type = 'NOTE' AND target_text IS NOT NULL
        AND num_nonnulls(target_transaction_id, target_account_id, target_card_id, target_invoice_id, target_person_id, target_debt_id,
            target_goal_id, target_key) = 0)),
    -- vínculo explícito: exatamente uma referência (id tipado, chave de mês ou o texto pendente)
    CONSTRAINT ck_note_links_explicit_single_reference CHECK (origin <> 'EXPLICIT' OR num_nonnulls(target_note_id, target_transaction_id,
        target_account_id, target_card_id, target_invoice_id, target_person_id, target_debt_id, target_goal_id, target_key, target_text) = 1)
);

CREATE INDEX ix_note_links_note ON note_links (note_id);
CREATE INDEX ix_note_links_user_target_note ON note_links (user_id, target_note_id) WHERE target_note_id IS NOT NULL;
CREATE INDEX ix_note_links_target_transaction ON note_links (target_transaction_id) WHERE target_transaction_id IS NOT NULL;
CREATE INDEX ix_note_links_target_account ON note_links (target_account_id) WHERE target_account_id IS NOT NULL;
CREATE INDEX ix_note_links_target_card ON note_links (target_card_id) WHERE target_card_id IS NOT NULL;
CREATE INDEX ix_note_links_target_invoice ON note_links (target_invoice_id) WHERE target_invoice_id IS NOT NULL;
CREATE INDEX ix_note_links_target_person ON note_links (target_person_id) WHERE target_person_id IS NOT NULL;
CREATE INDEX ix_note_links_target_debt ON note_links (target_debt_id) WHERE target_debt_id IS NOT NULL;
CREATE INDEX ix_note_links_target_goal ON note_links (target_goal_id) WHERE target_goal_id IS NOT NULL;
CREATE INDEX ix_note_links_user_target_key ON note_links (user_id, target_key) WHERE target_key IS NOT NULL;
