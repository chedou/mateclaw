-- V175: durable, channel-native troubleshooting intake sessions (MySQL 8).
-- Raw message bodies and attachment URLs/paths are deliberately not stored in
-- the receipt table; the aggregate contains only redacted fields and safe refs.

CREATE TABLE IF NOT EXISTS mate_troubleshooting_intake_session (
    id                BIGINT        NOT NULL PRIMARY KEY,
    workspace_id      BIGINT        NOT NULL,
    intake_session_id VARCHAR(96)   NOT NULL,
    active_key        VARCHAR(64)   NULL,
    routing_key       VARCHAR(64)   NOT NULL,
    source            VARCHAR(32)   NOT NULL,
    conversation_ref  VARCHAR(256)  NOT NULL,
    reporter_ref      VARCHAR(256)  NOT NULL,
    status            VARCHAR(32)   NOT NULL,
    last_message_at   TIMESTAMP(6)  NOT NULL,
    aggregate_json    LONGTEXT      NOT NULL,
    version           INT           NOT NULL DEFAULT 0,
    deleted           INT           NOT NULL DEFAULT 0,
    create_time       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ts_intake_session_id (workspace_id, intake_session_id),
    UNIQUE KEY uk_ts_intake_active (workspace_id, active_key),
    KEY idx_ts_intake_route_latest (workspace_id, routing_key, last_message_at),
    KEY idx_ts_intake_queue (workspace_id, status, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS mate_troubleshooting_intake_message (
    id                BIGINT        NOT NULL PRIMARY KEY,
    workspace_id      BIGINT        NOT NULL,
    source            VARCHAR(32)   NOT NULL,
    source_message_id VARCHAR(256)  NOT NULL,
    intake_session_id VARCHAR(96)   NOT NULL,
    received_at       TIMESTAMP(6)  NOT NULL,
    deleted           INT           NOT NULL DEFAULT 0,
    create_time       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ts_intake_source_message (workspace_id, source, source_message_id),
    KEY idx_ts_intake_message_session (workspace_id, intake_session_id, received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
