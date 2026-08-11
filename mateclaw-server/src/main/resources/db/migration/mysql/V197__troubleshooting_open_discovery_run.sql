-- V197: immutable budgets, selected plan and stop reason for bounded open discovery (MySQL 8).
-- Never stores prompts, model output, query text, observed values, endpoints or credentials.

CREATE TABLE IF NOT EXISTS mate_troubleshooting_open_discovery_run (
    id                     BIGINT        NOT NULL PRIMARY KEY,
    workspace_id           BIGINT        NOT NULL,
    run_id                 VARCHAR(128)  NOT NULL,
    diagnosis_id           VARCHAR(128)  NOT NULL,
    visible_scenario_keys  LONGTEXT      NOT NULL,
    selected_scenario_key  VARCHAR(128),
    planned_signal_kinds   LONGTEXT      NOT NULL,
    max_iterations         INT           NOT NULL,
    max_evidence_requests  INT           NOT NULL,
    source_request_count   INT           NOT NULL,
    time_budget_ms         BIGINT        NOT NULL,
    stop_reason            VARCHAR(64)   NOT NULL,
    evidence_refs          LONGTEXT      NOT NULL,
    actor_ref              VARCHAR(192)  NOT NULL,
    started_at             TIMESTAMP     NOT NULL,
    completed_at           TIMESTAMP     NOT NULL,
    deleted                INT           NOT NULL DEFAULT 0,
    create_time            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ts_open_discovery_run_id (workspace_id, run_id),
    KEY idx_ts_open_discovery_run_diagnosis (
        workspace_id, diagnosis_id, completed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
