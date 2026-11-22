-- =====================================================================================================
-- Replay protection for signed device requests (see docs/CONTRACTS.md, "Device ingestion"). Every accepted request
-- is remembered here until its timestamp leaves the accepted window (+-5 min, plus a 1-minute margin); the same
-- request again is refused with 409 REPLAYED_REQUEST and logged in ingest_rejections. request_key is the SHA-256 of
-- the request's nonce, or of its signature for devices that send no nonce. Expired rows are pruned every minute.
-- Platform-owned; nobody else reads or writes it. MySQL 8, valid under the production sql_mode. Times UTC.
-- =====================================================================================================

CREATE TABLE IF NOT EXISTS ingest_replay_guard (
    sensor_id   BIGINT      NOT NULL,           -- sensors.id
    request_key CHAR(64)    NOT NULL,           -- hex SHA-256 of "nonce:<nonce>" or "sig:<signature>"
    expires_at  DATETIME(3) NOT NULL,
    PRIMARY KEY (sensor_id, request_key),
    KEY ix_replay_guard_expires (expires_at)
);
