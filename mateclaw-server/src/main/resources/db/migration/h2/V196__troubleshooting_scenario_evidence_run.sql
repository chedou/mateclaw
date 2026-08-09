-- V196: immutable timing and safe references for generic scenario evidence runs (H2).
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
    evidence_refs     CLOB          NOT NULL,
    actor_ref         VARCHAR(192)  NOT NULL,
    started_at        TIMESTAMP     NOT NULL,
    completed_at      TIMESTAMP     NOT NULL,
    deleted           INT           NOT NULL DEFAULT 0,
    create_time       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ts_scenario_evidence_run_id
    ON mate_troubleshooting_scenario_evidence_run(workspace_id, run_id);
CREATE INDEX IF NOT EXISTS idx_ts_scenario_evidence_run_diagnosis
    ON mate_troubleshooting_scenario_evidence_run(
        workspace_id, diagnosis_id, completed_at);
