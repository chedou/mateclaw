-- V175: durable, channel-native troubleshooting intake sessions (H2).

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
    aggregate_json    CLOB          NOT NULL,
    version           INT           NOT NULL DEFAULT 0,
    deleted           INT           NOT NULL DEFAULT 0,
    create_time       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_ts_intake_session_id
    ON mate_troubleshooting_intake_session(workspace_id, intake_session_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ts_intake_active
    ON mate_troubleshooting_intake_session(workspace_id, active_key);
CREATE INDEX IF NOT EXISTS idx_ts_intake_route_latest
    ON mate_troubleshooting_intake_session(workspace_id, routing_key, last_message_at);
CREATE INDEX IF NOT EXISTS idx_ts_intake_queue
    ON mate_troubleshooting_intake_session(workspace_id, status, update_time);

CREATE TABLE IF NOT EXISTS mate_troubleshooting_intake_message (
    id                BIGINT        NOT NULL PRIMARY KEY,
    workspace_id      BIGINT        NOT NULL,
    source            VARCHAR(32)   NOT NULL,
    source_message_id VARCHAR(256)  NOT NULL,
    intake_session_id VARCHAR(96)   NOT NULL,
    received_at       TIMESTAMP(6)  NOT NULL,
    deleted           INT           NOT NULL DEFAULT 0,
    create_time       TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_ts_intake_source_message
    ON mate_troubleshooting_intake_message(workspace_id, source, source_message_id);
CREATE INDEX IF NOT EXISTS idx_ts_intake_message_session
    ON mate_troubleshooting_intake_message(workspace_id, intake_session_id, received_at);
