CREATE TABLE IF NOT EXISTS room_snapshots (
    room_id    VARCHAR(8)   NOT NULL PRIMARY KEY,
    snapshot   TEXT         NOT NULL,
    updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
