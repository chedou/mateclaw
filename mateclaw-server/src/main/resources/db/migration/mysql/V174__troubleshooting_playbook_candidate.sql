-- V174: evidence-derived PlaybookDraft review queue (MySQL 8).
-- This table contains no active/approved route and no write-execution queue.

CREATE TABLE IF NOT EXISTS mate_troubleshooting_playbook_candidate (
    id                   BIGINT       NOT NULL PRIMARY KEY,
    workspace_id         BIGINT       NOT NULL,
    record_id            VARCHAR(128) NOT NULL,
    generation_key       VARCHAR(64)  NOT NULL,
    source_incident_id   VARCHAR(128) NOT NULL,
    `system`             VARCHAR(96)  NOT NULL,
    service              VARCHAR(192) NOT NULL,
    scenario_key         VARCHAR(128) NOT NULL,
    origin               VARCHAR(32)  NOT NULL,
    review_status        VARCHAR(32)  NOT NULL,
    validation_status    VARCHAR(32)  NOT NULL,
    contract_version     VARCHAR(48)  NOT NULL,
    fixture_mode         BOOLEAN      NOT NULL DEFAULT TRUE,
    aggregate_json       LONGTEXT     NOT NULL,
    deleted              INT          NOT NULL DEFAULT 0,
    create_time          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ts_playbook_candidate_record (workspace_id, record_id),
    UNIQUE KEY uk_ts_playbook_candidate_generation (workspace_id, generation_key),
    KEY idx_ts_playbook_candidate_review (workspace_id, review_status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
