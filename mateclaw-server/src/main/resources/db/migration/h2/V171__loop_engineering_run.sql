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
    input_json             CLOB,
    step_results_json      CLOB,
    artifacts_json         CLOB,
    final_report_json      CLOB,
    started_at             TIMESTAMP,
    completed_at           TIMESTAMP,
    create_time            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted                INT          NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_loop_run_workspace_time
    ON mate_loop_run (workspace_id, create_time);

CREATE INDEX IF NOT EXISTS idx_loop_run_superpower
    ON mate_loop_run (workspace_id, superpower_skill_id, create_time);
