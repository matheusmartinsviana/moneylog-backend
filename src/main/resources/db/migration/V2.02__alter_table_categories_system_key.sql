-- Categorias da Etapa 2 (S-59, S-70):
-- 1) system_key: chave estável das categorias de sistema (seed idempotente no primeiro boot). Categoria do usuário não tem.
-- 2) nome único por (usuário, kind) entre as NÃO arquivadas, sem diferenciar maiúsculas (409 CATEGORY_NAME_TAKEN).
--    Arquivar libera o nome; desarquivar com nome já usado por uma ativa é recusado pela aplicação (e por este índice).
ALTER TABLE categories ADD COLUMN system_key VARCHAR(40);

ALTER TABLE categories
    ADD CONSTRAINT uq_categories_user_system_key UNIQUE (user_id, system_key);

CREATE UNIQUE INDEX uq_categories_user_kind_name_active ON categories (user_id, kind, lower(name)) WHERE NOT archived;
