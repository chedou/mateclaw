-- V198: claim an incident bucket before Agent/evidence work and freeze plan identity (KingbaseES).

ALTER TABLE mate_troubleshooting_open_discovery_run
    ADD COLUMN selected_plan_fingerprint VARCHAR(64);

CREATE TABLE IF NOT EXISTS mate_troubleshooting_open_discovery_claim (
    id                BIGINT        NOT NULL PRIMARY KEY,
    workspace_id      BIGINT        NOT NULL,
    dedup_key          VARCHAR(64)   NOT NULL,
    claim_token        VARCHAR(128)  NOT NULL,
    status             VARCHAR(32)   NOT NULL,
    diagnosis_id       VARCHAR(128),
    claimed_at         TIMESTAMP     NOT NULL,
    lease_expires_at   TIMESTAMP,
    completed_at       TIMESTAMP,
    create_time        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time        TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ts_open_discovery_claim_key
    ON mate_troubleshooting_open_discovery_claim(workspace_id, dedup_key);
CREATE INDEX IF NOT EXISTS idx_ts_open_discovery_claim_lease
    ON mate_troubleshooting_open_discovery_claim(status, lease_expires_at);
