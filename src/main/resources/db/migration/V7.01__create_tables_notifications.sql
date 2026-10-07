-- Central de notificações, lembretes e configurações (Etapa 7, T127). O texto é chave de i18n + parâmetros (S-34): nada de frase pronta.
CREATE TABLE notifications (
    id            UUID         NOT NULL,
    user_id       UUID         NOT NULL,
    kind          VARCHAR(24)  NOT NULL,
    ref_type      VARCHAR(16)  NOT NULL,
    -- nunca nulo: no Postgres NULLs não colidem em índice único e o job duplicaria (nos tipos sem entidade usa o próprio user_id)
    ref_id        UUID         NOT NULL,
    on_date       DATE         NOT NULL,
    title_key     VARCHAR(80)  NOT NULL,
    params        TEXT         NOT NULL DEFAULT '{}',
    due_on        DATE,
    push_eligible BOOLEAN      NOT NULL DEFAULT FALSE,
    push_sent_at  TIMESTAMPTZ,
    read_at       TIMESTAMPTZ,
    archived      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_notifications PRIMARY KEY (id),
    CONSTRAINT fk_notifications_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_notifications_kind CHECK (kind IN ('INVOICE_DUE', 'DEBT_COLLECT', 'DEBT_PAYABLE_DUE', 'RECURRENCE_PENDING',
        'COMMITTED_NEXT_MONTH', 'DAILY_SUMMARY', 'REMINDER', 'GOAL_DEADLINE', 'GROUPED'))
);

CREATE TABLE reminders (
    id          UUID         NOT NULL,
    user_id     UUID         NOT NULL,
    title       VARCHAR(120) NOT NULL,
    remind_on   DATE         NOT NULL,
    remind_at   TIME,
    note        VARCHAR(500),
    link_type   VARCHAR(20),
    link_id     UUID,
    done_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_reminders PRIMARY KEY (id),
    CONSTRAINT fk_reminders_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_reminders_link CHECK ((link_type IS NULL) = (link_id IS NULL))
);
CREATE INDEX ix_reminders_user_open ON reminders (user_id, remind_on) WHERE done_at IS NULL;

CREATE TABLE notification_settings (
    user_id            UUID         NOT NULL,
    summary_enabled    BOOLEAN      NOT NULL DEFAULT TRUE,
    summary_time       TIME         NOT NULL DEFAULT '08:00',
    summary_frequency  VARCHAR(10)  NOT NULL DEFAULT 'DAILY',
    -- tipos desligados, separados por vírgula (padrão: tudo ligado)
    disabled_kinds     VARCHAR(400) NOT NULL DEFAULT '',
    quiet_from         TIME         NOT NULL DEFAULT '22:00',
    quiet_to           TIME         NOT NULL DEFAULT '07:00',
    push_enabled       BOOLEAN      NOT NULL DEFAULT FALSE,
    display_name       VARCHAR(60),
    version            BIGINT       NOT NULL DEFAULT 1,
    updated_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_notification_settings PRIMARY KEY (user_id),
    CONSTRAINT fk_notification_settings_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_notification_settings_frequency CHECK (summary_frequency IN ('DAILY', 'ONLY_DUE', 'OFF'))
);
