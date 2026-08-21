-- V221: immutable, secret-free ledger for post-Diagnosis supplemental material.

CREATE TABLE mate_troubleshooting_diagnosis_follow_up_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workspace_id BIGINT NOT NULL,
    run_id VARCHAR(128) NOT NULL,
    diagnosis_id VARCHAR(128) NOT NULL,
    diagnosis_version INT NOT NULL,
    conclusion_type VARCHAR(32) NOT NULL,
    turn_kind VARCHAR(48) NOT NULL,
    content_length INT NOT NULL,
    disposition VARCHAR(48) NOT NULL DEFAULT 'RECORDED_NOT_VERIFIED',
    actor_ref VARCHAR(192) NOT NULL,
    recorded_at DATETIME(3) NOT NULL,
    deleted TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME(3) NOT NULL,
    update_time DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ts_follow_up_run (workspace_id, run_id),
    KEY idx_ts_follow_up_diagnosis (workspace_id, diagnosis_id, recorded_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
