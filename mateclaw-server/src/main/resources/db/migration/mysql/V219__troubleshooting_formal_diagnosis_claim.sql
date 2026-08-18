-- V219: claim a formal incident or IntakeSession before admission and Guance I/O (MySQL 8).

CREATE TABLE IF NOT EXISTS mate_troubleshooting_formal_diagnosis_claim (
    id                BIGINT        NOT NULL PRIMARY KEY,
    workspace_id      BIGINT        NOT NULL,
    dedup_key          VARCHAR(64)   NOT NULL,
    claim_token        VARCHAR(128)  NOT NULL,
    status             VARCHAR(32)   NOT NULL,
    diagnosis_id       VARCHAR(128)  NULL,
    claimed_at         TIMESTAMP(6)  NOT NULL,
    lease_expires_at   TIMESTAMP(6)  NULL,
    completed_at       TIMESTAMP(6)  NULL,
    create_time        TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    update_time        TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_ts_formal_diag_claim_key (workspace_id, dedup_key),
    KEY idx_ts_formal_diag_claim_lease (status, lease_expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
