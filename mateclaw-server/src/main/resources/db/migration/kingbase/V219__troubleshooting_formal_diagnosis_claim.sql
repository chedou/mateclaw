-- V219: claim a formal incident or IntakeSession before admission and Guance I/O (KingbaseES).

CREATE TABLE IF NOT EXISTS mate_troubleshooting_formal_diagnosis_claim (
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
CREATE UNIQUE INDEX IF NOT EXISTS uk_ts_formal_diag_claim_key
    ON mate_troubleshooting_formal_diagnosis_claim(workspace_id, dedup_key);
CREATE INDEX IF NOT EXISTS idx_ts_formal_diag_claim_lease
    ON mate_troubleshooting_formal_diagnosis_claim(status, lease_expires_at);
