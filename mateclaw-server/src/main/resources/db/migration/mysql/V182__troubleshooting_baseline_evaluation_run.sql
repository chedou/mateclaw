-- V182: secret-free, candidate-free single-Agent T8 baseline runs (MySQL 8).
-- Raw evidence, lookup material, draft text, abstain text and gate verdicts are absent.

CREATE TABLE IF NOT EXISTS mate_troubleshooting_baseline_eval_run (
    id                       BIGINT       NOT NULL PRIMARY KEY,
    workspace_id             BIGINT       NOT NULL,
    run_id                   VARCHAR(128) NOT NULL,
    run_key                  VARCHAR(64)  NOT NULL,
    sample_id                VARCHAR(128) NOT NULL,
    diagnosis_id             VARCHAR(128) NOT NULL,
    sample_version           INT          NOT NULL,
    source_platform          VARCHAR(32)  NOT NULL,
    evidence_fixture_mode    BOOLEAN      NOT NULL,
    diagnosis_fixture_mode   BOOLEAN      NOT NULL,
    run_status               VARCHAR(32)  NOT NULL,
    model_provider           VARCHAR(64)  NOT NULL,
    model_name               VARCHAR(192) NOT NULL,
    model_config_version     VARCHAR(320) NOT NULL,
    claim_token              VARCHAR(128) NOT NULL,
    reservation_expires_at   TIMESTAMP    NOT NULL,
    model_duration_ms        BIGINT,
    composed_total_ms        BIGINT,
    result_json              LONGTEXT,
    deleted                  INT          NOT NULL DEFAULT 0,
    create_time              TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ts_baseline_eval_run_id (workspace_id, run_id),
    UNIQUE KEY uk_ts_baseline_eval_run_key (workspace_id, run_key),
    KEY idx_ts_baseline_eval_sample (workspace_id, sample_id, create_time),
    KEY idx_ts_baseline_eval_diagnosis (workspace_id, diagnosis_id, create_time),
    KEY idx_ts_baseline_eval_claim (workspace_id, run_status, reservation_expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
