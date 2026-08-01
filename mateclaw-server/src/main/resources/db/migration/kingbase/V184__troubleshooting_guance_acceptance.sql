-- V184: immutable, secret-free T7 owner acceptance for one binding fingerprint (KingbaseES).
-- Query text, search keys, PS IDs, credentials and raw evidence are intentionally absent.

CREATE TABLE IF NOT EXISTS mate_troubleshooting_guance_acceptance (
    id                   BIGINT       NOT NULL PRIMARY KEY,
    workspace_id         BIGINT       NOT NULL,
    acceptance_id        VARCHAR(128) NOT NULL,
    scope_key            VARCHAR(64)  NOT NULL,
    binding_fingerprint  VARCHAR(64)  NOT NULL,
    system               VARCHAR(96)  NOT NULL,
    service              VARCHAR(192) NOT NULL,
    aggregate_json       TEXT         NOT NULL,
    version              INTEGER      NOT NULL DEFAULT 0,
    deleted              INTEGER      NOT NULL DEFAULT 0,
    create_time          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ts_guance_acceptance_id
    ON mate_troubleshooting_guance_acceptance(workspace_id, acceptance_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ts_guance_acceptance_binding
    ON mate_troubleshooting_guance_acceptance(
        workspace_id, scope_key, binding_fingerprint);
CREATE INDEX IF NOT EXISTS idx_ts_guance_acceptance_scope
    ON mate_troubleshooting_guance_acceptance(
        workspace_id, scope_key, create_time);
