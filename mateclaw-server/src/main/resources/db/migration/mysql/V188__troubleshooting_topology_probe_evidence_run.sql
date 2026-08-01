-- V188: immutable, Diagnosis-scoped safe projections for topology probe runs (MySQL 8).
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
    result_json    LONGTEXT      NOT NULL,
    actor_ref      VARCHAR(192)  NOT NULL,
    started_at     TIMESTAMP     NOT NULL,
    completed_at   TIMESTAMP     NOT NULL,
    deleted        INT           NOT NULL DEFAULT 0,
    create_time    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ts_topology_probe_run_id (workspace_id, run_id),
    KEY idx_ts_topology_probe_run_diagnosis (
        workspace_id, diagnosis_id, completed_at),
    KEY idx_ts_topology_probe_run_topology (
        workspace_id, topology_id, completed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
