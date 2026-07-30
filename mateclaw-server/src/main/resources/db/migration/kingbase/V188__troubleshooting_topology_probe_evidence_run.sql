-- V188: immutable, Diagnosis-scoped safe projections for topology probe runs (KingbaseES).
-- Raw Guance responses, DQL, credentials and arbitrary query URLs are not stored.

CREATE TABLE IF NOT EXISTS mate_troubleshooting_topology_probe_run (
    id             BIGINT        NOT NULL PRIMARY KEY,
    workspace_id   BIGINT        NOT NULL,
    run_id         VARCHAR(128)  NOT NULL,
    diagnosis_id   VARCHAR(128)  NOT NULL,
    topology_id    VARCHAR(128)  NOT NULL,
    scenario_key   VARCHAR(128)  NOT NULL,
    tool_key       VARCHAR(128)  NOT NULL,
    status         VARCHAR(64)   NOT NULL,
    result_json    TEXT          NOT NULL,
    actor_ref      VARCHAR(192)  NOT NULL,
    started_at     TIMESTAMP     NOT NULL,
    completed_at   TIMESTAMP     NOT NULL,
    deleted        INTEGER       NOT NULL DEFAULT 0,
    create_time    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ts_topology_probe_run_id
    ON mate_troubleshooting_topology_probe_run(workspace_id, run_id);
CREATE INDEX IF NOT EXISTS idx_ts_topology_probe_run_diagnosis
    ON mate_troubleshooting_topology_probe_run(
        workspace_id, diagnosis_id, completed_at);
CREATE INDEX IF NOT EXISTS idx_ts_topology_probe_run_topology
    ON mate_troubleshooting_topology_probe_run(
        workspace_id, topology_id, completed_at);
