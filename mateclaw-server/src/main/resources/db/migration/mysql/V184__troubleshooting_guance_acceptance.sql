-- V184: immutable, secret-free T7 owner acceptance for one binding fingerprint (MySQL 8).
-- Query text, search keys, PS IDs, credentials and raw evidence are intentionally absent.

CREATE TABLE IF NOT EXISTS mate_troubleshooting_guance_acceptance (
    id                   BIGINT       NOT NULL PRIMARY KEY,
    workspace_id         BIGINT       NOT NULL,
    acceptance_id        VARCHAR(128) NOT NULL,
    scope_key            VARCHAR(64)  NOT NULL,
    binding_fingerprint  VARCHAR(64)  NOT NULL,
    system               VARCHAR(96)  NOT NULL,
    service              VARCHAR(192) NOT NULL,
    aggregate_json       LONGTEXT     NOT NULL,
    version              INT          NOT NULL DEFAULT 0,
    deleted              INT          NOT NULL DEFAULT 0,
    create_time          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ts_guance_acceptance_id (workspace_id, acceptance_id),
    UNIQUE KEY uk_ts_guance_acceptance_binding (
        workspace_id, scope_key, binding_fingerprint),
    KEY idx_ts_guance_acceptance_scope (workspace_id, scope_key, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
