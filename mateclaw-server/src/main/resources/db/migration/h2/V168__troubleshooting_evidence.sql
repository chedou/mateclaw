-- V168: Evidence records collected for troubleshooting SOP runs.

ALTER TABLE mate_troubleshooting_sop_run
    ADD COLUMN IF NOT EXISTS alert_json CLOB;

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
    summary        CLOB,
    content_json   CLOB,
    collected_at   TIMESTAMP,
    create_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted        INT          NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_troubleshooting_evidence_run
    ON mate_troubleshooting_evidence (workspace_id, run_id, evidence_type);

CREATE INDEX IF NOT EXISTS idx_troubleshooting_evidence_case
    ON mate_troubleshooting_evidence (workspace_id, case_id, create_time);
