-- V222: idempotent pointer ledger for MySQL-backed troubleshooting chat turns.
CREATE TABLE mate_troubleshooting_chat_turn (
    id BIGINT NOT NULL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    conversation_id VARCHAR(128) NOT NULL,
    client_turn_id VARCHAR(128) NOT NULL,
    agent_id BIGINT NOT NULL,
    user_message_id BIGINT NOT NULL,
    assistant_message_id BIGINT NOT NULL,
    deleted SMALLINT NOT NULL DEFAULT 0,
    create_time TIMESTAMP(3) NOT NULL,
    update_time TIMESTAMP(3) NOT NULL,
    CONSTRAINT uk_ts_chat_turn UNIQUE (workspace_id, conversation_id, client_turn_id)
);
CREATE INDEX idx_ts_chat_messages ON mate_troubleshooting_chat_turn
    (conversation_id, user_message_id, assistant_message_id);
