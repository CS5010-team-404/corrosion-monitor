-- AUDIT SERVICE: the hash-chained audit log.
--
-- V1 created an audit_log table that nothing chains into. It is renamed here, not dropped, so no data is lost.
-- Its two append-only triggers stay on the renamed table, and it can be dropped once you are sure it is empty.
-- audit_outbox (created in V2) is not touched here. The audit worker only sets its chained_at column.

RENAME TABLE audit_log TO audit_log_v1;

-- One row per audit event, chained to the one before it.
-- Columns and sizes mirror audit_outbox, plus the chain fields. Times are DATETIME(3), like the rest of the schema.
-- details is TEXT, not JSON: it holds the exact text that was hashed, and MySQL may reformat JSON values.
CREATE TABLE audit_log (
                           id          BIGINT       NOT NULL,
                           outbox_id   BIGINT       NOT NULL,
                           occurred_at DATETIME(3)  NOT NULL,
                           recorded_at DATETIME(3)  NOT NULL,
                           actor_type  VARCHAR(16)  NOT NULL,
                           actor       VARCHAR(64)  NOT NULL,
                           action      VARCHAR(64)  NOT NULL,
                           entity_type VARCHAR(32)  NULL,
                           entity_id   VARCHAR(64)  NULL,
                           details     MEDIUMTEXT   NULL,
                           prev_hash   CHAR(64)     NOT NULL,
                           entry_hash  CHAR(64)     NOT NULL,
                           PRIMARY KEY (id),
                           UNIQUE KEY uq_audit_log_outbox (outbox_id),
                           UNIQUE KEY uq_audit_log_entry_hash (entry_hash),
                           UNIQUE KEY uq_audit_log_prev_hash (prev_hash),
                           KEY idx_audit_log_occurred (occurred_at),
                           KEY idx_audit_log_actor (actor, occurred_at),
                           KEY idx_audit_log_action (action, occurred_at),
                           KEY idx_audit_log_entity (entity_type, entity_id)
) ENGINE=InnoDB;

-- A single row. Locking it is what lets only one transaction extend the chain at a time.
CREATE TABLE audit_chain_head (
                                  id        TINYINT  NOT NULL DEFAULT 1,
                                  last_id   BIGINT   NOT NULL,
                                  last_hash CHAR(64) NOT NULL,
                                  PRIMARY KEY (id),
                                  CONSTRAINT chk_audit_chain_head_single CHECK (id = 1)
) ENGINE=InnoDB;

INSERT INTO audit_chain_head (id, last_id, last_hash)
VALUES (1, 0, '0000000000000000000000000000000000000000000000000000000000000000');

-- Results of the Admin verify button.
CREATE TABLE audit_verification (
                                    id              BIGINT       NOT NULL AUTO_INCREMENT,
                                    run_at          DATETIME(3)  NOT NULL,
                                    run_by          VARCHAR(255) NOT NULL,
                                    entries_checked BIGINT       NOT NULL,
                                    valid           BOOLEAN      NOT NULL,
                                    first_broken_id BIGINT       NULL,
                                    message         VARCHAR(255) NULL,
                                    PRIMARY KEY (id)
) ENGINE=InnoDB;

-- Copies of the head hash taken on a schedule. Copy them somewhere the application cannot write too,
-- otherwise someone who rewrites the whole chain could rewrite these as well.
CREATE TABLE audit_checkpoint (
                                  id         BIGINT      NOT NULL AUTO_INCREMENT,
                                  created_at DATETIME(3) NOT NULL,
                                  last_id    BIGINT      NOT NULL,
                                  last_hash  CHAR(64)    NOT NULL,
                                  PRIMARY KEY (id)
) ENGINE=InnoDB;

-- Append-only at the database level, same idea as the V1 triggers. Single-statement bodies need no DELIMITER.
-- The trigger names differ from V1 because trigger names are unique per schema, and the V1 ones still exist.
CREATE TRIGGER audit_log_no_update BEFORE UPDATE ON audit_log
    FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_log is append-only: UPDATE not allowed';

CREATE TRIGGER audit_log_no_delete BEFORE DELETE ON audit_log
    FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'audit_log is append-only: DELETE not allowed';
