-- V167: Troubleshooting SOP execution trace.
-- SOP definitions live in mate_skill; this table records per-case SOP runs,
-- validation results, and final report payloads.

CREATE TABLE IF NOT EXISTS mate_troubleshooting_sop_run (
    id                     BIGINT       NOT NULL PRIMARY KEY,
    workspace_id           BIGINT       NOT NULL,
    case_id                VARCHAR(128) NOT NULL,
    sop_skill_id           BIGINT,
    sop_name               VARCHAR(128),
    sop_version            VARCHAR(64),
    domain                 VARCHAR(64),
    scenario               VARCHAR(128),
    confidence             DOUBLE,
    status                 VARCHAR(32)  NOT NULL DEFAULT 'pending',
    route_reason           CLOB,
    step_results_json      CLOB,
    final_report_json      CLOB,
    validation_errors_json CLOB,
    started_at             TIMESTAMP,
    completed_at           TIMESTAMP,
    create_time            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time            TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted                INT          NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_troubleshooting_sop_run_case
    ON mate_troubleshooting_sop_run (workspace_id, case_id, create_time);

CREATE INDEX IF NOT EXISTS idx_troubleshooting_sop_run_sop
    ON mate_troubleshooting_sop_run (workspace_id, sop_skill_id, create_time);
