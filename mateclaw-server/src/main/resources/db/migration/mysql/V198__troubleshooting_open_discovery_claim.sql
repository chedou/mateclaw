-- V198: claim an incident bucket before Agent/evidence work and freeze plan identity (MySQL 8).

ALTER TABLE mate_troubleshooting_open_discovery_run
    ADD COLUMN selected_plan_fingerprint VARCHAR(64) NULL
        AFTER selected_scenario_key;

CREATE TABLE IF NOT EXISTS mate_troubleshooting_open_discovery_claim (
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
    UNIQUE KEY uk_ts_open_discovery_claim_key (workspace_id, dedup_key),
    KEY idx_ts_open_discovery_claim_lease (status, lease_expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
