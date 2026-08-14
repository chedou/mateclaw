-- V187: immutable, workspace-shared deployment topology snapshots (MySQL 8).
-- Analysis results, raw Guance responses, DQL and credentials are not stored.

CREATE TABLE IF NOT EXISTS mate_troubleshooting_deployment_topology (
    id                       BIGINT        NOT NULL PRIMARY KEY,
    workspace_id             BIGINT        NOT NULL,
    topology_id              VARCHAR(128)  NOT NULL,
    name                     VARCHAR(128)  NOT NULL,
    `system`                 VARCHAR(128)  NOT NULL,
    system_label             VARCHAR(256)  NOT NULL,
    schema_version           VARCHAR(32)   NOT NULL,
    exported_at              TIMESTAMP     NOT NULL,
    snapshot_json            LONGTEXT      NOT NULL,
    snapshot_fingerprint     VARCHAR(64)   NOT NULL,
    node_count               INT           NOT NULL,
    link_count               INT           NOT NULL,
    configured_probe_nodes   INT           NOT NULL,
    imported_by              VARCHAR(192)  NOT NULL,
    deleted                  INT           NOT NULL DEFAULT 0,
    create_time              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time              TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ts_deployment_topology_id (workspace_id, topology_id),
    UNIQUE KEY uk_ts_deployment_topology_name (workspace_id, name, deleted),
    UNIQUE KEY uk_ts_deployment_topology_fingerprint (
        workspace_id, snapshot_fingerprint, deleted),
    KEY idx_ts_deployment_topology_created (workspace_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
