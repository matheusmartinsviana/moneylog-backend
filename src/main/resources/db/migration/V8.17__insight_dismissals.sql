-- Dispensa de insights calculados (ADR-019): auditoria SÓ de inserção. Dispensar grava uma linha; reverter grava revoked_at na mesma linha;
-- nunca se apaga nem se edita outra coisa. Aditiva: nenhuma tabela existente muda.
--   TODAY: some só no dia da dispensa (mesmo insight); MONTH: some o mesmo tipo e assunto até o fim do mês da dispensa;
--   TYPE: "parar de avisar deste tipo" até reverter.
CREATE TABLE insight_dismissals (
    id           UUID         NOT NULL,
    user_id      UUID         NOT NULL,
    fingerprint  VARCHAR(32)  NOT NULL,
    type         VARCHAR(40)  NOT NULL,
    subject_id   VARCHAR(160),
    scope        VARCHAR(8)   NOT NULL,
    dismissed_on DATE         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL,
    revoked_at   TIMESTAMPTZ,
    CONSTRAINT pk_insight_dismissals PRIMARY KEY (id),
    CONSTRAINT fk_insight_dismissals_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_insight_dismissals_scope CHECK (scope IN ('TODAY', 'MONTH', 'TYPE')),
    CONSTRAINT ck_insight_dismissals_revoked CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);
-- a consulta de toda listagem: as dispensas ainda ativas do usuário
CREATE INDEX ix_insight_dismissals_active ON insight_dismissals (user_id) WHERE revoked_at IS NULL;

-- só inserção: não apaga, e a única mudança permitida é revogar uma vez (revoked_at de nulo para um instante)
CREATE FUNCTION insight_dismissals_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'insight_dismissals é só de inserção: não se apaga uma dispensa';
    END IF;
    IF OLD.revoked_at IS NOT NULL OR NEW.revoked_at IS NULL
        OR (NEW.id, NEW.user_id, NEW.fingerprint, NEW.type, NEW.subject_id, NEW.scope, NEW.dismissed_on, NEW.created_at)
           IS DISTINCT FROM (OLD.id, OLD.user_id, OLD.fingerprint, OLD.type, OLD.subject_id, OLD.scope, OLD.dismissed_on, OLD.created_at) THEN
        RAISE EXCEPTION 'insight_dismissals é só de inserção: a única mudança permitida é gravar revoked_at uma vez';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_insight_dismissals_guard BEFORE UPDATE OR DELETE
    ON insight_dismissals
    FOR EACH ROW EXECUTE FUNCTION insight_dismissals_guard();
