-- V201: immutable workspace troubleshooting pilot-plan revisions (MySQL 8).

CREATE TABLE IF NOT EXISTS mate_troubleshooting_pilot_plan (
    id                    BIGINT        NOT NULL PRIMARY KEY,
    workspace_id          BIGINT        NOT NULL,
    plan_name             VARCHAR(120)  NOT NULL,
    module_scopes         TEXT          NOT NULL,
    second_line_user_id   BIGINT        NOT NULL,
    third_line_user_id    BIGINT        NOT NULL,
    source_owner_user_id  BIGINT        NOT NULL,
    enabled               TINYINT(1)    NOT NULL,
    version               INT           NOT NULL,
    changed_by            VARCHAR(128)  NOT NULL,
    change_reason         VARCHAR(300)  NOT NULL,
    create_time           TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_ts_pilot_plan_workspace_version (workspace_id, version),
    KEY idx_ts_pilot_plan_workspace_created (workspace_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
