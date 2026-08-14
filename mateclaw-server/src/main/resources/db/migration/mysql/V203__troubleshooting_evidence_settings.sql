-- V203: workspace-scoped evidence source settings (MySQL 8).
--
-- Moves the Guance / Recorded Replay / miss-path agent switches out of
-- application.yml, where they were process-wide and needed a restart, into a
-- per-workspace row an owner can edit at runtime. A workspace with no row here
-- keeps falling back to the deployment yml, so existing installs are unchanged
-- until someone writes a row on purpose.
--
-- guance_api_key holds the SettingCrypto envelope (enc:v1:<base64>), never a
-- plaintext key. It is nullable so a workspace can declare an endpoint before
-- the credential exists, and so clearing the key is distinguishable from
-- "leave it alone" at the service layer.

CREATE TABLE IF NOT EXISTS mate_troubleshooting_evidence_settings (
    workspace_id               BIGINT        NOT NULL PRIMARY KEY,
    guance_enabled             TINYINT(1)    NOT NULL DEFAULT 0,
    guance_base_url            VARCHAR(512)  NULL,
    guance_api_key             VARCHAR(1024) NULL,
    guance_allow_insecure_http TINYINT(1)    NOT NULL DEFAULT 0,
    replay_enabled             TINYINT(1)    NOT NULL DEFAULT 0,
    agent_enabled              TINYINT(1)    NOT NULL DEFAULT 0,
    -- Optimistic lock. The client echoes the version it read; a mismatch means
    -- someone else changed the row and the write is rejected rather than
    -- silently overwriting another owner's credential.
    version                    INT           NOT NULL DEFAULT 0,
    changed_by                 VARCHAR(128)  NULL,
    change_reason              VARCHAR(512)  NULL,
    create_time                TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_time                TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
