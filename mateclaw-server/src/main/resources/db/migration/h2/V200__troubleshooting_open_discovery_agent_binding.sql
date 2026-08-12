-- V200: workspace-scoped OPEN_DISCOVERY digital-employee binding (H2).

CREATE TABLE IF NOT EXISTS mate_troubleshooting_open_discovery_agent_binding (
    workspace_id      BIGINT        NOT NULL PRIMARY KEY,
    agent_id           BIGINT        NOT NULL,
    bound_by           VARCHAR(128),
    bound_at           TIMESTAMP     NOT NULL,
    create_time        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_ts_od_agent_binding_agent
    ON mate_troubleshooting_open_discovery_agent_binding(agent_id);
