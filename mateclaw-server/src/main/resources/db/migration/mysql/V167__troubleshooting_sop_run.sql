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
    route_reason           MEDIUMTEXT,
    step_results_json      MEDIUMTEXT,
    final_report_json      MEDIUMTEXT,
    validation_errors_json MEDIUMTEXT,
    started_at             DATETIME(3),
    completed_at           DATETIME(3),
    create_time            DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time            DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted                INT          NOT NULL DEFAULT 0,
    KEY idx_troubleshooting_sop_run_case (workspace_id, case_id, create_time),
    KEY idx_troubleshooting_sop_run_sop (workspace_id, sop_skill_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Troubleshooting SOP execution traces linked to alert cases.';
