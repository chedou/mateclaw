-- V186: immutable approved Playbook versions and review-time authority baseline (H2).
-- Candidate sources remain in their source stores. Only a reviewed promotion writes
-- this table. A nullable active_selector_key makes the database, rather than an
-- application-side count, the final single-active authority for one selector.

ALTER TABLE mate_troubleshooting_knowledge_review
    ADD COLUMN active_baseline_known BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE mate_troubleshooting_knowledge_review
    ADD COLUMN base_playbook_id VARCHAR(128);
ALTER TABLE mate_troubleshooting_knowledge_review
    ADD COLUMN base_playbook_version INT;

-- Manual source identity is sop_id. Multiple immutable candidate sources may
-- propose later versions for one selector; authority uniqueness lives below.
DROP INDEX IF EXISTS uk_ts_sop_route;
CREATE INDEX IF NOT EXISTS idx_ts_sop_route_sources
    ON mate_troubleshooting_sop(workspace_id, route_key, create_time);

CREATE TABLE IF NOT EXISTS mate_troubleshooting_playbook_version (
    id                     BIGINT        NOT NULL PRIMARY KEY,
    workspace_id           BIGINT        NOT NULL,
    playbook_id            VARCHAR(128)  NOT NULL,
    selector_key           VARCHAR(256)  NOT NULL,
    playbook_version       INT           NOT NULL,
    active_selector_key    VARCHAR(256),
    system                 VARCHAR(96)   NOT NULL,
    error_code             VARCHAR(128)  NOT NULL,
    service                VARCHAR(192)  NOT NULL,
    status                 VARCHAR(32)   NOT NULL,
    source_origin          VARCHAR(32)   NOT NULL,
    source_record_id       VARCHAR(128)  NOT NULL,
    review_id              VARCHAR(128),
    review_version         INT,
    approved_by            VARCHAR(192)  NOT NULL,
    approval_reason        VARCHAR(1000) NOT NULL,
    approval_snapshot_json CLOB,
    deprecated_by          VARCHAR(192),
    deprecation_reason     VARCHAR(1000),
    deprecated_at          TIMESTAMP,
    contract_version       VARCHAR(32)   NOT NULL,
    aggregate_json         CLOB          NOT NULL,
    version                INT           NOT NULL DEFAULT 0,
    deleted                INT           NOT NULL DEFAULT 0,
    create_time            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time            TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_ts_playbook_version_id
    ON mate_troubleshooting_playbook_version(workspace_id, playbook_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ts_playbook_version_number
    ON mate_troubleshooting_playbook_version(
        workspace_id, selector_key, playbook_version);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ts_playbook_version_active
    ON mate_troubleshooting_playbook_version(
        workspace_id, active_selector_key);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ts_playbook_version_review
    ON mate_troubleshooting_playbook_version(workspace_id, review_id);
CREATE INDEX IF NOT EXISTS idx_ts_playbook_version_selector
    ON mate_troubleshooting_playbook_version(
        workspace_id, selector_key, playbook_version);

-- Existing verified approved rows predate the review ledger. Preserve them as
-- v1 authority without inventing a reviewer or qualification snapshot.
INSERT INTO mate_troubleshooting_playbook_version (
    id, workspace_id, playbook_id, selector_key, playbook_version,
    active_selector_key, system, error_code, service, status,
    source_origin, source_record_id, review_id, review_version,
    approved_by, approval_reason, approval_snapshot_json,
    contract_version, aggregate_json, version, deleted,
    create_time, update_time
)
SELECT id, workspace_id, sop_id, route_key, 1,
       route_key, system, error_code, service, 'APPROVED',
       'LEGACY', sop_id, NULL, NULL,
       'legacy-migration', 'V186 legacy approved backfill', NULL,
       contract_version, aggregate_json, 0, deleted,
       create_time, update_time
  FROM mate_troubleshooting_sop
 WHERE status = 'approved'
   AND verified = TRUE
   AND deleted = 0;

-- Reviews already IN_REVIEW when V186 lands freeze the authority visible at
-- migration time. Without this, the V185 source uniqueness key would leave
-- them permanently unable to approve or restart.
UPDATE mate_troubleshooting_knowledge_review review_row
   SET active_baseline_known = TRUE,
       base_playbook_id = (
           SELECT version_row.playbook_id
             FROM mate_troubleshooting_playbook_version version_row
            WHERE version_row.workspace_id = review_row.workspace_id
              AND version_row.active_selector_key = review_row.selector_key
              AND version_row.status = 'APPROVED'
              AND version_row.deleted = 0
       ),
       base_playbook_version = (
           SELECT version_row.playbook_version
             FROM mate_troubleshooting_playbook_version version_row
            WHERE version_row.workspace_id = review_row.workspace_id
              AND version_row.active_selector_key = review_row.selector_key
              AND version_row.status = 'APPROVED'
              AND version_row.deleted = 0
       )
 WHERE review_row.status = 'IN_REVIEW'
   AND review_row.deleted = 0
   AND review_row.active_baseline_known = FALSE;
