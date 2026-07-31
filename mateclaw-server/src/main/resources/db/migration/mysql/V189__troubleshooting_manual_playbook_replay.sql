-- V189: immutable manual-candidate replay attestations (MySQL 8).
-- Only bounded counters and fingerprints are stored; raw replay facts are not.

CREATE TABLE IF NOT EXISTS mate_troubleshooting_manual_playbook_replay (
    id                    BIGINT        NOT NULL PRIMARY KEY,
    workspace_id          BIGINT        NOT NULL,
    attestation_id        VARCHAR(128)  NOT NULL,
    source_record_id      VARCHAR(128)  NOT NULL,
    selector_key          VARCHAR(256)  NOT NULL,
    candidate_fingerprint VARCHAR(64)   NOT NULL,
    suite_id              VARCHAR(128)  NOT NULL,
    suite_version         INT           NOT NULL,
    suite_fingerprint     VARCHAR(64)   NOT NULL,
    status                VARCHAR(32)   NOT NULL,
    result_json           LONGTEXT      NOT NULL,
    executed_by           VARCHAR(192)  NOT NULL,
    executed_at           TIMESTAMP     NOT NULL,
    deleted               INT           NOT NULL DEFAULT 0,
    create_time           TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time           TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ts_manual_replay_id (workspace_id, attestation_id),
    UNIQUE KEY uk_ts_manual_replay_exact (
        workspace_id, source_record_id, candidate_fingerprint, suite_fingerprint),
    KEY idx_ts_manual_replay_source (
        workspace_id, source_record_id, executed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
