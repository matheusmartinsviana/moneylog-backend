-- Assinaturas de push (Etapa 7, T130). Preparado e desligado por padrão (moneylog.push.enabled=false): a central é o canal garantido.
CREATE TABLE push_subscriptions (
    id          UUID         NOT NULL,
    user_id     UUID         NOT NULL,
    endpoint    VARCHAR(500) NOT NULL,
    p256dh      VARCHAR(200) NOT NULL,
    auth        VARCHAR(100) NOT NULL,
    user_agent  VARCHAR(200),
    created_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_push_subscriptions PRIMARY KEY (id),
    CONSTRAINT fk_push_subscriptions_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uq_push_subscriptions_user_endpoint UNIQUE (user_id, endpoint)
);
