-- V178: durable READY -> Diagnosis investigation hand-off (KingbaseES).

ALTER TABLE mate_troubleshooting_diagnosis
    ADD COLUMN source_intake_session_id VARCHAR(96) NULL;

CREATE UNIQUE INDEX uk_ts_diagnosis_intake
    ON mate_troubleshooting_diagnosis(workspace_id, source_intake_session_id);

CREATE TABLE mate_troubleshooting_intake_investigation (
    id                BIGINT        NOT NULL PRIMARY KEY,
    workspace_id      BIGINT        NOT NULL,
    intake_session_id VARCHAR(96)   NOT NULL,
    diagnosis_id      VARCHAR(96)   NULL,
    status            VARCHAR(32)   NOT NULL,
    attempts          INTEGER       NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMP(6)  NULL,
    claimed_by        VARCHAR(160)  NULL,
    lease_expires_at  TIMESTAMP(6)  NULL,
    last_error        VARCHAR(2000) NULL,
    completed_at      TIMESTAMP(6)  NULL,
    deleted           INTEGER       NOT NULL DEFAULT 0,
    create_time       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_ts_intake_investigation
    ON mate_troubleshooting_intake_investigation(workspace_id, intake_session_id);
CREATE INDEX idx_ts_intake_investigation_due
    ON mate_troubleshooting_intake_investigation(status, next_attempt_at, lease_expires_at);
