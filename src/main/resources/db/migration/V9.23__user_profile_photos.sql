CREATE TABLE user_profile_photos (
    user_id     UUID         NOT NULL,
    display     BYTEA        NOT NULL,
    thumbnail   BYTEA        NOT NULL,
    width       INTEGER      NOT NULL,
    height      INTEGER      NOT NULL,
    size_bytes  BIGINT       NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT pk_user_profile_photos PRIMARY KEY (user_id),
    CONSTRAINT fk_user_profile_photos_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_user_profile_photos_size CHECK (width > 0 AND height > 0 AND size_bytes > 0)
);
