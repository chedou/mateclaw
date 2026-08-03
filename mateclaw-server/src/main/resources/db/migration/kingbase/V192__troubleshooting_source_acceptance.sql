-- V192: platform-agnostic, secret-free owner acceptance for one evidence source binding.
--
-- Generalizes V184 (Guance-only) so a second adapter does not mean a second table
-- and a second half-remembered set of rules (A9). Endpoints, credentials, query
-- text and raw evidence are intentionally absent: only the binding fingerprint,
-- the owner's itemised attestation, and what the server itself re-observed.

CREATE TABLE IF NOT EXISTS mate_troubleshooting_source_acceptance (
    id                   BIGINT       NOT NULL PRIMARY KEY,
    workspace_id         BIGINT       NOT NULL,
    acceptance_id        VARCHAR(128) NOT NULL,
    platform             VARCHAR(64)  NOT NULL,
    binding_fingerprint  VARCHAR(64)  NOT NULL,
    aggregate_json       CLOB         NOT NULL,
    version              INT          NOT NULL DEFAULT 0,
    deleted              INT          NOT NULL DEFAULT 0,
    create_time          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ts_source_acceptance_id
    ON mate_troubleshooting_source_acceptance(workspace_id, acceptance_id);
-- One acceptance per (workspace, platform, exact binding). A configuration change
-- yields a different fingerprint, so the old row simply stops matching and the
-- source reads as STALE — nothing has to remember to invalidate it.
CREATE UNIQUE INDEX IF NOT EXISTS uk_ts_source_acceptance_binding
    ON mate_troubleshooting_source_acceptance(
        workspace_id, platform, binding_fingerprint);
CREATE INDEX IF NOT EXISTS idx_ts_source_acceptance_platform
    ON mate_troubleshooting_source_acceptance(workspace_id, platform, create_time);
