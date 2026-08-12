-- V200: workspace-scoped OPEN_DISCOVERY digital-employee binding (MySQL 8).

CREATE TABLE IF NOT EXISTS mate_troubleshooting_open_discovery_agent_binding (
    workspace_id      BIGINT        NOT NULL PRIMARY KEY,
    agent_id           BIGINT        NOT NULL,
    bound_by           VARCHAR(128)  NULL,
    bound_at           TIMESTAMP(6)  NOT NULL,
    create_time        TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_time        TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    KEY idx_ts_od_agent_binding_agent (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
