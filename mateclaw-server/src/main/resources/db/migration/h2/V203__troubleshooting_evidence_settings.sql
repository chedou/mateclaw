-- V203: workspace-scoped evidence source settings (H2).
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
    guance_enabled             BOOLEAN       NOT NULL DEFAULT FALSE,
    guance_base_url            VARCHAR(512),
    guance_api_key             VARCHAR(1024),
    guance_allow_insecure_http BOOLEAN       NOT NULL DEFAULT FALSE,
    replay_enabled             BOOLEAN       NOT NULL DEFAULT FALSE,
    agent_enabled              BOOLEAN       NOT NULL DEFAULT FALSE,
    -- Optimistic lock. The client echoes the version it read; a mismatch means
    -- someone else changed the row and the write is rejected rather than
    -- silently overwriting another owner's credential.
    version                    INT           NOT NULL DEFAULT 0,
    changed_by                 VARCHAR(128),
    change_reason              VARCHAR(512),
    create_time                TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time                TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
