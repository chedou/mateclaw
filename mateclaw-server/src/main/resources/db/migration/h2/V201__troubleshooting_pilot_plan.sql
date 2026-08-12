-- V201: immutable workspace troubleshooting pilot-plan revisions (H2).

CREATE TABLE IF NOT EXISTS mate_troubleshooting_pilot_plan (
    id                    BIGINT        NOT NULL PRIMARY KEY,
    workspace_id          BIGINT        NOT NULL,
    plan_name             VARCHAR(120)  NOT NULL,
    module_scopes         CLOB          NOT NULL,
    second_line_user_id   BIGINT        NOT NULL,
    third_line_user_id    BIGINT        NOT NULL,
    source_owner_user_id  BIGINT        NOT NULL,
    enabled               BOOLEAN       NOT NULL,
    version               INT           NOT NULL,
    changed_by            VARCHAR(128)  NOT NULL,
    change_reason         VARCHAR(300)  NOT NULL,
    create_time           TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_ts_pilot_plan_workspace_version UNIQUE (workspace_id, version)
);
CREATE INDEX IF NOT EXISTS idx_ts_pilot_plan_workspace_created
    ON mate_troubleshooting_pilot_plan(workspace_id, create_time);
