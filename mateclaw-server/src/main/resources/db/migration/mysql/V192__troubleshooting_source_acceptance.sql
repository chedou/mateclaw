-- V192: platform-agnostic, secret-free owner acceptance for one evidence source binding.
--
-- Carries the V184 discipline to adapters whose binding IS its fingerprint, so a
-- second adapter does not mean a second table and a second half-remembered set of
-- rules (A9). Endpoints, credentials, query text and raw evidence are intentionally
-- absent: only the binding fingerprint, the owner's itemised attestation, and what
-- the server itself re-observed.
--
-- It does NOT supersede V184, and this table is not a migration target for it.
-- Here the subject is one platform's adapter binding (workspace, platform) --
-- Prometheus and Elasticsearch, whose binding is an endpoint plus a field mapping.
-- V184's subject is one system's observability asset (workspace, system, service),
-- and it additionally demands a full live canonical chain, a fingerprint compared
-- before and after, and enough frozen executable recording targets. That is the T7
-- gate, an order of magnitude stronger. Folding V184 into this table would weaken
-- T7 into "the adapter answered", and answering is not the same as querying the
-- right thing.

CREATE TABLE IF NOT EXISTS mate_troubleshooting_source_acceptance (
    id                   BIGINT       NOT NULL PRIMARY KEY,
    workspace_id         BIGINT       NOT NULL,
    acceptance_id        VARCHAR(128) NOT NULL,
    platform             VARCHAR(64)  NOT NULL,
    binding_fingerprint  VARCHAR(64)  NOT NULL,
    aggregate_json       LONGTEXT     NOT NULL,
    version              INT          NOT NULL DEFAULT 0,
    deleted              INT          NOT NULL DEFAULT 0,
    create_time          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ts_source_acceptance_id (workspace_id, acceptance_id),
    -- One acceptance per (workspace, platform, exact binding). A configuration
    -- change yields a different fingerprint, so the old row simply stops matching
    -- and the source reads as STALE — nothing has to remember to invalidate it.
    UNIQUE KEY uk_ts_source_acceptance_binding (
        workspace_id, platform, binding_fingerprint),
    KEY idx_ts_source_acceptance_platform (workspace_id, platform, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
