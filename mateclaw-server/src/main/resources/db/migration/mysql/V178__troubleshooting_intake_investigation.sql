-- V178: durable READY -> Diagnosis investigation hand-off (MySQL 8).

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
    attempts          INT           NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMP(6)  NULL,
    claimed_by        VARCHAR(160)  NULL,
    lease_expires_at  TIMESTAMP(6)  NULL,
    last_error        VARCHAR(2000) NULL,
    completed_at      TIMESTAMP(6)  NULL,
    deleted           INT           NOT NULL DEFAULT 0,
    create_time       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ts_intake_investigation (workspace_id, intake_session_id),
    KEY idx_ts_intake_investigation_due (status, next_attempt_at, lease_expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
