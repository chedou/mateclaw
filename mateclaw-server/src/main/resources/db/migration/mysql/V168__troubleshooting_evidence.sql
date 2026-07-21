-- V168: Evidence records collected for troubleshooting SOP runs.

ALTER TABLE mate_troubleshooting_sop_run
    ADD COLUMN alert_json MEDIUMTEXT NULL AFTER route_reason;

CREATE TABLE IF NOT EXISTS mate_troubleshooting_evidence (
    id             BIGINT       NOT NULL PRIMARY KEY,
    workspace_id   BIGINT       NOT NULL,
    case_id        VARCHAR(128) NOT NULL,
    run_id         BIGINT       NOT NULL,
    evidence_id    VARCHAR(128) NOT NULL,
    evidence_type  VARCHAR(64)  NOT NULL,
    source         VARCHAR(128) NOT NULL,
    status         VARCHAR(32)  NOT NULL,
    title          VARCHAR(256),
    summary        MEDIUMTEXT,
    content_json   MEDIUMTEXT,
    collected_at   DATETIME(3),
    create_time    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted        INT          NOT NULL DEFAULT 0,
    KEY idx_troubleshooting_evidence_run (workspace_id, run_id, evidence_type),
    KEY idx_troubleshooting_evidence_case (workspace_id, case_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Troubleshooting evidence records collected by SOP runs.';
