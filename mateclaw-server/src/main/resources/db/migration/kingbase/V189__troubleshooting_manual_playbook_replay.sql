-- V189: immutable manual-candidate replay attestations (KingbaseES).
-- Only bounded counters and fingerprints are stored; raw replay facts are not.

CREATE TABLE IF NOT EXISTS mate_troubleshooting_manual_playbook_replay (
    id                    BIGINT        NOT NULL PRIMARY KEY,
    workspace_id          BIGINT        NOT NULL,
    attestation_id        VARCHAR(128)  NOT NULL,
    source_record_id      VARCHAR(128)  NOT NULL,
    selector_key          VARCHAR(256)  NOT NULL,
    candidate_fingerprint VARCHAR(64)   NOT NULL,
    suite_id              VARCHAR(128)  NOT NULL,
    suite_version         INTEGER       NOT NULL,
    suite_fingerprint     VARCHAR(64)   NOT NULL,
    status                VARCHAR(32)   NOT NULL,
    result_json           TEXT          NOT NULL,
    executed_by           VARCHAR(192)  NOT NULL,
    executed_at           TIMESTAMP     NOT NULL,
    deleted               INTEGER       NOT NULL DEFAULT 0,
    create_time           TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time           TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ts_manual_replay_id
    ON mate_troubleshooting_manual_playbook_replay(
        workspace_id, attestation_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ts_manual_replay_exact
    ON mate_troubleshooting_manual_playbook_replay(
        workspace_id, source_record_id, candidate_fingerprint, suite_fingerprint);
CREATE INDEX IF NOT EXISTS idx_ts_manual_replay_source
    ON mate_troubleshooting_manual_playbook_replay(
        workspace_id, source_record_id, executed_at);
