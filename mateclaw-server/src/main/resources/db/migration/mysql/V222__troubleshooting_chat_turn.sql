-- V222: idempotent pointer ledger for MySQL-backed troubleshooting chat turns.
CREATE TABLE mate_troubleshooting_chat_turn (
    id BIGINT NOT NULL,
    workspace_id BIGINT NOT NULL,
    conversation_id VARCHAR(128) NOT NULL,
    client_turn_id VARCHAR(128) NOT NULL,
    agent_id BIGINT NOT NULL,
    user_message_id BIGINT NOT NULL,
    assistant_message_id BIGINT NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME(3) NOT NULL,
    update_time DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ts_chat_turn (workspace_id, conversation_id, client_turn_id),
    KEY idx_ts_chat_messages (conversation_id, user_message_id, assistant_message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
