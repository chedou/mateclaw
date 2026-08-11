-- V197: immutable budgets, selected plan and stop reason for bounded open discovery (H2).
-- Never stores prompts, model output, query text, observed values, endpoints or credentials.

CREATE TABLE IF NOT EXISTS mate_troubleshooting_open_discovery_run (
    id                     BIGINT        NOT NULL PRIMARY KEY,
    workspace_id           BIGINT        NOT NULL,
    run_id                 VARCHAR(128)  NOT NULL,
    diagnosis_id           VARCHAR(128)  NOT NULL,
    visible_scenario_keys  CLOB          NOT NULL,
    selected_scenario_key  VARCHAR(128),
    planned_signal_kinds   CLOB          NOT NULL,
    max_iterations         INT           NOT NULL,
    max_evidence_requests  INT           NOT NULL,
    source_request_count   INT           NOT NULL,
    time_budget_ms         BIGINT        NOT NULL,
    stop_reason            VARCHAR(64)   NOT NULL,
    evidence_refs          CLOB          NOT NULL,
    actor_ref              VARCHAR(192)  NOT NULL,
    started_at             TIMESTAMP     NOT NULL,
    completed_at           TIMESTAMP     NOT NULL,
    deleted                INT           NOT NULL DEFAULT 0,
    create_time            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ts_open_discovery_run_id
    ON mate_troubleshooting_open_discovery_run(workspace_id, run_id);
CREATE INDEX IF NOT EXISTS idx_ts_open_discovery_run_diagnosis
    ON mate_troubleshooting_open_discovery_run(
        workspace_id, diagnosis_id, completed_at);
