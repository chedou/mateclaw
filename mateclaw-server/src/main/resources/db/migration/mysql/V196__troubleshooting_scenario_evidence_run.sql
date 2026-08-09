-- V196: immutable timing and safe references for generic scenario evidence runs (MySQL 8).
-- The table never stores query text, observed values, raw logs, endpoints or credentials.

CREATE TABLE IF NOT EXISTS mate_troubleshooting_scenario_evidence_run (
    id                BIGINT        NOT NULL PRIMARY KEY,
    workspace_id      BIGINT        NOT NULL,
    run_id            VARCHAR(128)  NOT NULL,
    diagnosis_id      VARCHAR(128)  NOT NULL,
    playbook_id       VARCHAR(128)  NOT NULL,
    playbook_version  INT           NOT NULL,
    diagnosis_status  VARCHAR(64)   NOT NULL,
    conclusion_type   VARCHAR(64)   NOT NULL,
    evidence_refs     LONGTEXT      NOT NULL,
    actor_ref         VARCHAR(192)  NOT NULL,
    started_at        TIMESTAMP     NOT NULL,
    completed_at      TIMESTAMP     NOT NULL,
    deleted           INT           NOT NULL DEFAULT 0,
    create_time       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ts_scenario_evidence_run_id (workspace_id, run_id),
    KEY idx_ts_scenario_evidence_run_diagnosis (
        workspace_id, diagnosis_id, completed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
