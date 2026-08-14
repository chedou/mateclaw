-- V193: per-workspace evidence routing, so onboarding a system stops requiring a redeploy.
--
-- Until now `mateclaw.troubleshooting.evidence.routes` was the only route table,
-- and it lives in application.yml. Two consequences, both bad:
--
--   1. A new tenant could register a Playbook, approve it, open a case and run its
--      evidence plan — and every request came back MISSING/router:unconfigured,
--      because adding their system meant editing a file inside the release.
--   2. The YAML map is keyed by system name ALONE. Any workspace that names its
--      system "CSDP" inherits CSDP's route and reaches CSDP's observability
--      endpoint. Adding workspace_id here closes that, it does not open anything.
--
-- What a route may say is deliberately narrow: an ordered list of platform names.
-- Endpoints and credentials stay with the operator-configured adapters, so a
-- tenant chooses AMONG already-enabled sources and can never introduce a new one.

CREATE TABLE IF NOT EXISTS mate_troubleshooting_evidence_route (
    id            BIGINT       NOT NULL PRIMARY KEY,
    workspace_id  BIGINT       NOT NULL,
    `system`      VARCHAR(128) NOT NULL,
    signal_kind   VARCHAR(128) NOT NULL,
    platforms     VARCHAR(512) NOT NULL,
    updated_by    VARCHAR(128) NOT NULL,
    reason        VARCHAR(500) NOT NULL,
    version       INT          NOT NULL DEFAULT 0,
    deleted       INT          NOT NULL DEFAULT 0,
    create_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    -- Exactly one live route per (workspace, system, signal kind): two rows
    -- answering the same question would let insertion order decide which
    -- source is queried.
    UNIQUE KEY uk_ts_evidence_route (workspace_id, `system`, signal_kind),
    KEY idx_ts_evidence_route_system (workspace_id, `system`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
