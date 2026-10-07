-- Teto absoluto da sessão (B7): o início da família de refresh tokens. Cada rotação herda o valor do token anterior; a
-- expiração do sucessor nunca passa de family_started_at + teto (90 dias por padrão), então a janela deixa de ser
-- deslizante para sempre.
ALTER TABLE refresh_tokens ADD COLUMN family_started_at TIMESTAMPTZ;

UPDATE refresh_tokens t
   SET family_started_at = (SELECT MIN(f.created_at) FROM refresh_tokens f WHERE f.family_id = t.family_id);

ALTER TABLE refresh_tokens ADD CONSTRAINT ck_refresh_tokens_family_started CHECK (family_started_at <= created_at);

-- Sem NOT NULL de propósito: linha sem o valor (inserida por quem não conhece a coluna) conta como família que começou em
-- created_at (o mapper faz o COALESCE); o código novo sempre grava.

-- limpeza por expiração (B7)
CREATE INDEX ix_refresh_tokens_expires_at ON refresh_tokens (expires_at);
