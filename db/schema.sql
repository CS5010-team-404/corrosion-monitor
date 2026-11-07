-- SUPERSEDED: the schema is now created by Flyway from backend/src/main/resources/db/migration
-- (V1__team_schema.sql is this file, V2 and later hold the agreed changes; see docs/DECISIONS.md).
-- Kept for reference only: do not run it. Change the schema with a new migration, not here.
--
-- Electronics Corrosion Prevention: MySQL 8.0.16+ schema (v1)
-- CHECK constraints are only enforced from 8.0.16 onward. Use InnoDB (default).
-- Store and read all timestamps in UTC: connect with serverTimezone=UTC.

CREATE DATABASE IF NOT EXISTS corrosion_monitor
    CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE corrosion_monitor;

CREATE TABLE app_user (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    name           VARCHAR(100) NOT NULL,
    email          VARCHAR(255) NOT NULL UNIQUE,
    password_hash  VARCHAR(100) NOT NULL,                 -- BCrypt
    role           VARCHAR(20)  NOT NULL,
    failed_logins  INT NOT NULL DEFAULT 0,
    locked_until   DATETIME(3) NULL,
    created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_user_role CHECK (role IN ('ADMIN','OPERATOR','MANAGER'))
) ENGINE=InnoDB;

-- Equipment profile = threshold set (e.g. server rack, outdoor enclosure).
-- Fill values from cited sources (e.g. ASHRAE TC 9.9). Do not invent numbers.
CREATE TABLE equipment_profile (
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    name                   VARCHAR(100) NOT NULL UNIQUE,
    rh_warn_pct            DECIMAL(5,2) NOT NULL,
    rh_critical_pct        DECIMAL(5,2) NOT NULL,
    dew_margin_warn_c      DECIMAL(5,2) NOT NULL,         -- warn if (surface temp - dew point) below this
    dew_margin_critical_c  DECIMAL(5,2) NOT NULL,
    risk_budget            DECIMAL(10,2) NOT NULL,        -- damage units that map to risk score 1.0
    peck_n                 DECIMAL(4,2) NOT NULL DEFAULT 2.66,
    peck_ea_ev             DECIMAL(4,2) NOT NULL DEFAULT 0.79,
    ref_temp_c             DECIMAL(5,2) NOT NULL DEFAULT 25,
    ref_rh_pct             DECIMAL(5,2) NOT NULL DEFAULT 40
) ENGINE=InnoDB;

CREATE TABLE location (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    name         VARCHAR(100) NOT NULL,
    description  VARCHAR(500),
    profile_id   BIGINT NOT NULL,
    FOREIGN KEY (profile_id) REFERENCES equipment_profile(id)
) ENGINE=InnoDB;

CREATE TABLE sensor (
    id           CHAR(36) PRIMARY KEY,                    -- UUID as text
    location_id  BIGINT NOT NULL,
    secret_enc   VARCHAR(255) NOT NULL,                   -- HMAC key, encrypted at rest
    last_seq     BIGINT NOT NULL DEFAULT 0,
    last_seen    DATETIME(3) NULL,
    health       VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN',
    created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    FOREIGN KEY (location_id) REFERENCES location(id),
    CONSTRAINT chk_sensor_health CHECK (health IN ('OK','SILENT','STUCK','IMPLAUSIBLE','UNKNOWN'))
) ENGINE=InnoDB;

CREATE TABLE reading (
    sensor_id      CHAR(36)    NOT NULL,
    seq            BIGINT      NOT NULL,
    ts             DATETIME(3) NOT NULL,
    temperature_c  DECIMAL(5,2) NOT NULL,
    humidity_pct   DECIMAL(5,2) NOT NULL,
    dew_point_c    DECIMAL(5,2) NULL,                     -- computed on ingest
    buffered       BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (sensor_id, seq),                         -- also blocks duplicate seq
    FOREIGN KEY (sensor_id) REFERENCES sensor(id),
    CONSTRAINT chk_rh   CHECK (humidity_pct BETWEEN 0 AND 100),
    CONSTRAINT chk_temp CHECK (temperature_c BETWEEN -40 AND 125),
    INDEX idx_reading_ts (sensor_id, ts)
) ENGINE=InnoDB;

-- Replay protection: purge old nonces with a scheduled job (see bottom)
CREATE TABLE used_nonce (
    sensor_id  CHAR(36)     NOT NULL,
    nonce      VARCHAR(64)  NOT NULL,
    seen_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (sensor_id, nonce),
    INDEX idx_nonce_seen (seen_at)
) ENGINE=InnoDB;

CREATE TABLE risk_state (
    location_id        BIGINT PRIMARY KEY,
    cumulative_damage  DECIMAL(14,4) NOT NULL DEFAULT 0,
    window_start       DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    risk_score         DECIMAL(6,4)  NOT NULL DEFAULT 0,
    updated_at         DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    FOREIGN KEY (location_id) REFERENCES location(id)
) ENGINE=InnoDB;

CREATE TABLE forecast (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    location_id    BIGINT NOT NULL,
    made_at        DATETIME(3) NOT NULL,
    horizon_min    INT NOT NULL,
    model          VARCHAR(30) NOT NULL,                  -- LINEAR, HOLT, ...
    pred_temp_c    DECIMAL(5,2),
    pred_rh_pct    DECIMAL(5,2),
    pred_margin_c  DECIMAL(5,2),
    FOREIGN KEY (location_id) REFERENCES location(id),
    INDEX idx_forecast_loc (location_id, made_at)
) ENGINE=InnoDB;

CREATE TABLE alert (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    location_id      BIGINT NOT NULL,
    type             VARCHAR(40) NOT NULL,
    severity         VARCHAR(10) NOT NULL,
    message          VARCHAR(500) NOT NULL,
    status           VARCHAR(15) NOT NULL DEFAULT 'OPEN',
    opened_at        DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    escalated_at     DATETIME(3) NULL,
    acknowledged_by  BIGINT NULL,
    acknowledged_at  DATETIME(3) NULL,
    ack_note         VARCHAR(500),
    FOREIGN KEY (location_id) REFERENCES location(id),
    FOREIGN KEY (acknowledged_by) REFERENCES app_user(id),
    CONSTRAINT chk_alert_sev    CHECK (severity IN ('WARNING','CRITICAL')),
    CONSTRAINT chk_alert_status CHECK (status IN ('OPEN','ACKNOWLEDGED','CLOSED')),
    INDEX idx_alert_status (status, severity)
) ENGINE=InnoDB;

CREATE TABLE corrective_action (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    alert_id     BIGINT NOT NULL,
    user_id      BIGINT NOT NULL,
    action_text  VARCHAR(1000) NOT NULL,
    created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    FOREIGN KEY (alert_id) REFERENCES alert(id),
    FOREIGN KEY (user_id)  REFERENCES app_user(id)
) ENGINE=InnoDB;

-- Append-only audit log (prev_hash/hash columns are optional: keep them if you
-- want tamper detection in addition to append-only enforcement)
CREATE TABLE audit_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    ts          DATETIME(3) NOT NULL,
    event_type  VARCHAR(40) NOT NULL,
    user_id     BIGINT NULL,
    source_ip   VARCHAR(45) NULL,                         -- fits IPv6
    resource    VARCHAR(100) NULL,
    outcome     VARCHAR(10) NOT NULL,
    details     VARCHAR(1000) NULL,
    prev_hash   CHAR(64) NOT NULL,
    hash        CHAR(64) NOT NULL UNIQUE,
    CONSTRAINT chk_audit_outcome CHECK (outcome IN ('SUCCESS','FAILURE'))
) ENGINE=InnoDB;

-- Block UPDATE and DELETE. MySQL needs one trigger per event.
-- If running in the mysql client, wrap with DELIMITER $$ ... $$ DELIMITER ;
-- (not needed when run through Flyway, Liquibase, or Docker init scripts that split statements for you).
DELIMITER $$
CREATE TRIGGER audit_no_update BEFORE UPDATE ON audit_log
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_log is append-only';
END$$

CREATE TRIGGER audit_no_delete BEFORE DELETE ON audit_log
FOR EACH ROW
BEGIN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_log is append-only';
END$$
DELIMITER ;

-- Least privilege: the app account can only INSERT and SELECT audit records.
-- Choose the password from an environment variable; never commit it.
-- CREATE USER 'app_rw'@'%' IDENTIFIED BY '<from-env>';
-- GRANT SELECT, INSERT, UPDATE, DELETE ON corrosion_monitor.* TO 'app_rw'@'%';
-- REVOKE UPDATE, DELETE ON corrosion_monitor.audit_log FROM 'app_rw'@'%';
--   (if the REVOKE fails because the grant was schema-wide, grant table by table instead)

-- Nonce cleanup (needs: SET GLOBAL event_scheduler = ON;)
-- CREATE EVENT purge_nonces ON SCHEDULE EVERY 1 HOUR
--   DO DELETE FROM used_nonce WHERE seen_at < NOW(3) - INTERVAL 1 DAY;

-- Seed data (replace values with cited ones)
-- INSERT INTO equipment_profile (name, rh_warn_pct, rh_critical_pct, dew_margin_warn_c,
--     dew_margin_critical_c, risk_budget) VALUES ('Server rack', ..., ..., ..., ..., ...);
