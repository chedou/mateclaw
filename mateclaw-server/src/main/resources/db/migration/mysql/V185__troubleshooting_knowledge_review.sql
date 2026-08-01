-- V185: independent knowledge-review decisions with optimistic versioning (MySQL 8).
-- Publication delivery remains in the outbox. Raw evidence, query text, search
-- terms and credentials are intentionally excluded from this audit ledger.

CREATE TABLE IF NOT EXISTS mate_troubleshooting_knowledge_review (
    id                BIGINT        NOT NULL PRIMARY KEY,
    workspace_id      BIGINT        NOT NULL,
    review_id         VARCHAR(128)  NOT NULL,
    origin            VARCHAR(32)   NOT NULL,
    source_record_id  VARCHAR(128)  NOT NULL,
    selector_key      VARCHAR(256),
    status            VARCHAR(32)   NOT NULL,
    reviewer          VARCHAR(192)  NOT NULL,
    reason            VARCHAR(1000) NOT NULL,
    snapshot_json     LONGTEXT      NOT NULL,
    version           INT           NOT NULL DEFAULT 0,
    deleted           INT           NOT NULL DEFAULT 0,
    create_time       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ts_knowledge_review_id (workspace_id, review_id),
    UNIQUE KEY uk_ts_knowledge_review_source (
        workspace_id, origin, source_record_id),
    KEY idx_ts_knowledge_review_status (workspace_id, status, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
