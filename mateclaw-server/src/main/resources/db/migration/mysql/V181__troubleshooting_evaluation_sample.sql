-- V181: secret-free historical Evidence Spine samples for T8 calibration (MySQL 8).
-- Search terms, source query text, raw logs and credentials are intentionally absent.

CREATE TABLE IF NOT EXISTS mate_troubleshooting_evaluation_sample (
    id                      BIGINT       NOT NULL PRIMARY KEY,
    workspace_id            BIGINT       NOT NULL,
    sample_id               VARCHAR(128) NOT NULL,
    sample_key              VARCHAR(64)  NOT NULL,
    diagnosis_id            VARCHAR(128) NOT NULL,
    `system`                VARCHAR(96)  NOT NULL,
    service                 VARCHAR(192) NOT NULL,
    scenario_key            VARCHAR(128) NOT NULL,
    source_platform         VARCHAR(32)  NOT NULL,
    evidence_stage          VARCHAR(32)  NOT NULL,
    reference_status        VARCHAR(32)  NOT NULL,
    fixture_mode            BOOLEAN      NOT NULL DEFAULT FALSE,
    diagnosis_fixture_mode  BOOLEAN      NOT NULL DEFAULT TRUE,
    aggregate_json          LONGTEXT     NOT NULL,
    version                 INT          NOT NULL DEFAULT 0,
    deleted                 INT          NOT NULL DEFAULT 0,
    create_time             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ts_eval_sample_id (workspace_id, sample_id),
    UNIQUE KEY uk_ts_eval_sample_key (workspace_id, sample_key),
    KEY idx_ts_eval_sample_status (workspace_id, reference_status, create_time),
    KEY idx_ts_eval_sample_diagnosis (workspace_id, diagnosis_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
