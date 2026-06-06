CREATE TABLE IF NOT EXISTS room_snapshots (
    room_id    VARCHAR(8)   NOT NULL PRIMARY KEY,
    snapshot   TEXT         NOT NULL,
    updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS users (
    id           VARCHAR(36)   NOT NULL PRIMARY KEY,
    provider     VARCHAR(20)   NOT NULL,
    provider_id  VARCHAR(255)  NOT NULL,
    email        VARCHAR(255),
    display_name VARCHAR(255),
    avatar_url   VARCHAR(1024),
    created_at   TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_users_provider UNIQUE (provider, provider_id)
);
