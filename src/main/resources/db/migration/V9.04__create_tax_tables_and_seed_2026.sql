CREATE TABLE tax_tables (
    id                          UUID         NOT NULL,
    kind                        VARCHAR(8)   NOT NULL,
    version                     INTEGER      NOT NULL,
    effective_from              DATE         NOT NULL,
    effective_to                DATE,
    basis                       VARCHAR(16)  NOT NULL,
    method                      VARCHAR(24)  NOT NULL,
    contribution_ceiling_cents  BIGINT,
    dependent_deduction_cents   BIGINT,
    simplified_limit_cents      BIGINT,
    reduction_full_up_to_cents  BIGINT,
    reduction_full_cents        BIGINT,
    reduction_phase_out_cents   BIGINT,
    reduction_constant_cents    BIGINT,
    reduction_coefficient_micros BIGINT,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_tax_tables PRIMARY KEY (id),
    CONSTRAINT uq_tax_tables_kind_from_version UNIQUE (kind, effective_from, version),
    CONSTRAINT ck_tax_tables_kind CHECK (kind IN ('INSS', 'IRRF')),
    CONSTRAINT ck_tax_tables_basis CHECK ((kind = 'INSS' AND basis = 'COMPETENCE') OR (kind = 'IRRF' AND basis = 'PAYMENT_DATE')),
    CONSTRAINT ck_tax_tables_method CHECK (
        (kind = 'INSS' AND method = 'PROGRESSIVE_BY_BAND') OR (kind = 'IRRF' AND method = 'RATE_MINUS_DEDUCTION')),
    CONSTRAINT ck_tax_tables_version CHECK (version >= 1),
    CONSTRAINT ck_tax_tables_period CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT ck_tax_tables_reduction CHECK (
        (reduction_full_up_to_cents IS NULL AND reduction_full_cents IS NULL AND reduction_phase_out_cents IS NULL
            AND reduction_constant_cents IS NULL AND reduction_coefficient_micros IS NULL)
        OR (reduction_full_up_to_cents IS NOT NULL AND reduction_full_cents IS NOT NULL AND reduction_phase_out_cents IS NOT NULL
            AND reduction_constant_cents IS NOT NULL AND reduction_coefficient_micros IS NOT NULL))
);

CREATE TABLE tax_table_brackets (
    tax_table_id    UUID     NOT NULL,
    position        SMALLINT NOT NULL,
    from_cents      BIGINT   NOT NULL,
    up_to_cents     BIGINT,
    rate_bps        INTEGER  NOT NULL,
    deduction_cents BIGINT   NOT NULL DEFAULT 0,
    CONSTRAINT pk_tax_table_brackets PRIMARY KEY (tax_table_id, position),
    CONSTRAINT fk_tax_table_brackets_table FOREIGN KEY (tax_table_id) REFERENCES tax_tables (id),
    CONSTRAINT ck_tax_table_brackets_rate CHECK (rate_bps BETWEEN 0 AND 10000),
    CONSTRAINT ck_tax_table_brackets_range CHECK (from_cents >= 0 AND (up_to_cents IS NULL OR up_to_cents >= from_cents)),
    CONSTRAINT ck_tax_table_brackets_deduction CHECK (deduction_cents >= 0)
);

CREATE TABLE tax_table_sources (
    tax_table_id UUID          NOT NULL,
    position     SMALLINT      NOT NULL,
    agency       VARCHAR(200)  NOT NULL,
    norm         VARCHAR(300)  NOT NULL,
    url          VARCHAR(500)  NOT NULL,
    consulted_on DATE          NOT NULL,
    CONSTRAINT pk_tax_table_sources PRIMARY KEY (tax_table_id, position),
    CONSTRAINT fk_tax_table_sources_table FOREIGN KEY (tax_table_id) REFERENCES tax_tables (id)
);

INSERT INTO tax_tables (id, kind, version, effective_from, effective_to, basis, method, contribution_ceiling_cents)
VALUES ('5a1f0c3e-7c11-4b6e-9a01-000000002601', 'INSS', 1, DATE '2026-01-01', NULL, 'COMPETENCE', 'PROGRESSIVE_BY_BAND', 847555);

INSERT INTO tax_table_brackets (tax_table_id, position, from_cents, up_to_cents, rate_bps, deduction_cents) VALUES
    ('5a1f0c3e-7c11-4b6e-9a01-000000002601', 1, 0, 162100, 750, 0),
    ('5a1f0c3e-7c11-4b6e-9a01-000000002601', 2, 162101, 290284, 900, 0),
    ('5a1f0c3e-7c11-4b6e-9a01-000000002601', 3, 290285, 435427, 1200, 0),
    ('5a1f0c3e-7c11-4b6e-9a01-000000002601', 4, 435428, 847555, 1400, 0);

INSERT INTO tax_table_sources (tax_table_id, position, agency, norm, url, consulted_on) VALUES
    ('5a1f0c3e-7c11-4b6e-9a01-000000002601', 1, 'Instituto Nacional do Seguro Social / Ministério da Previdência Social',
     'Portaria Interministerial MPS/MF nº 13, de 09/01/2026',
     'https://www.gov.br/inss/pt-br/direitos-e-deveres/inscricao-e-contribuicao/tabela-de-contribuicao-mensal', DATE '2026-10-15');

INSERT INTO tax_tables (id, kind, version, effective_from, effective_to, basis, method, dependent_deduction_cents, simplified_limit_cents,
                        reduction_full_up_to_cents, reduction_full_cents, reduction_phase_out_cents, reduction_constant_cents,
                        reduction_coefficient_micros)
VALUES ('5a1f0c3e-7c11-4b6e-9a01-000000002602', 'IRRF', 1, DATE '2026-01-01', NULL, 'PAYMENT_DATE', 'RATE_MINUS_DEDUCTION', 18959, 60720,
        500000, 31289, 735000, 97862, 133145);

INSERT INTO tax_table_brackets (tax_table_id, position, from_cents, up_to_cents, rate_bps, deduction_cents) VALUES
    ('5a1f0c3e-7c11-4b6e-9a01-000000002602', 1, 0, 242880, 0, 0),
    ('5a1f0c3e-7c11-4b6e-9a01-000000002602', 2, 242881, 282665, 750, 18216),
    ('5a1f0c3e-7c11-4b6e-9a01-000000002602', 3, 282666, 375105, 1500, 39416),
    ('5a1f0c3e-7c11-4b6e-9a01-000000002602', 4, 375106, 466468, 2250, 67549),
    ('5a1f0c3e-7c11-4b6e-9a01-000000002602', 5, 466469, NULL, 2750, 90873);

INSERT INTO tax_table_sources (tax_table_id, position, agency, norm, url, consulted_on) VALUES
    ('5a1f0c3e-7c11-4b6e-9a01-000000002602', 1, 'Receita Federal',
     'Lei nº 15.191, de 11/08/2025 (tabela progressiva mensal, dedução por dependente e desconto simplificado)',
     'https://www.gov.br/receitafederal/pt-br/assuntos/meu-imposto-de-renda/tabelas/2026', DATE '2026-10-15'),
    ('5a1f0c3e-7c11-4b6e-9a01-000000002602', 2, 'Presidência da República (Planalto)',
     'Lei nº 15.270, de 26/11/2025 (art. 3º-A da Lei nº 9.250/1995, redutor mensal, efeitos a partir de 01/01/2026)',
     'https://www.planalto.gov.br/ccivil_03/_ato2023-2026/2025/lei/l15270.htm', DATE '2026-10-15');
