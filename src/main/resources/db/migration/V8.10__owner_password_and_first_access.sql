-- Senha do dono no banco e fluxo de primeiro acesso (ADR-015). Aditiva: nada existente muda.
-- password_hash: BCrypt da senha escolhida pelo dono. Quando existe, vale mais que o hash do ambiente.
-- password_setup_pending: o dono pediu para redefinir (MONEYLOG_OWNER_RESET_PASSWORD) e ainda não escolheu a nova senha;
--   enquanto true, o hash do ambiente NÃO autentica.
-- setup_code_hash: SHA-256 (hex) do código de uso único do primeiro acesso; o código em claro nunca é gravado.
ALTER TABLE users
    ADD COLUMN password_hash          VARCHAR(100),
    ADD COLUMN password_set_at        TIMESTAMPTZ,
    ADD COLUMN password_setup_pending BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN setup_code_hash        VARCHAR(64),
    ADD COLUMN setup_code_expires_at  TIMESTAMPTZ;
