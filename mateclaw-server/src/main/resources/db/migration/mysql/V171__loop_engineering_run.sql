-- V171: Loop Engineering run shell.
-- Superpower definitions live in mate_skill; this table records planned and
-- executed engineering-loop runs separately from troubleshooting SOP runs.

CREATE TABLE IF NOT EXISTS mate_loop_run (
    id                     BIGINT       NOT NULL PRIMARY KEY,
    workspace_id           BIGINT       NOT NULL,
    superpower_skill_id    BIGINT,
    superpower_name        VARCHAR(128),
    superpower_version     VARCHAR(64),
    domain                 VARCHAR(64),
    scenario               VARCHAR(128),
    status                 VARCHAR(32)  NOT NULL DEFAULT 'planned',
    input_json             MEDIUMTEXT,
    step_results_json      MEDIUMTEXT,
    artifacts_json         MEDIUMTEXT,
    final_report_json      MEDIUMTEXT,
    started_at             DATETIME(3),
    completed_at           DATETIME(3),
    create_time            DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time            DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted                INT          NOT NULL DEFAULT 0,
    KEY idx_loop_run_workspace_time (workspace_id, create_time),
    KEY idx_loop_run_superpower (workspace_id, superpower_skill_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Loop Engineering superpower run traces.';
