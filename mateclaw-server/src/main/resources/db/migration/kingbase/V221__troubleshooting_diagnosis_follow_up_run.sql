-- V221: immutable, secret-free ledger for post-Diagnosis supplemental material.

CREATE TABLE mate_troubleshooting_diagnosis_follow_up_run (
    id BIGSERIAL PRIMARY KEY,
    workspace_id BIGINT NOT NULL,
    run_id VARCHAR(128) NOT NULL,
    diagnosis_id VARCHAR(128) NOT NULL,
    diagnosis_version INT NOT NULL,
    conclusion_type VARCHAR(32) NOT NULL,
    turn_kind VARCHAR(48) NOT NULL,
    content_length INT NOT NULL,
    disposition VARCHAR(48) DEFAULT 'RECORDED_NOT_VERIFIED' NOT NULL,
    actor_ref VARCHAR(192) NOT NULL,
    recorded_at TIMESTAMP(3) NOT NULL,
    deleted INT DEFAULT 0 NOT NULL,
    create_time TIMESTAMP(3) NOT NULL,
    update_time TIMESTAMP(3) NOT NULL,
    CONSTRAINT uk_ts_follow_up_run UNIQUE (workspace_id, run_id)
);

CREATE INDEX idx_ts_follow_up_diagnosis
    ON mate_troubleshooting_diagnosis_follow_up_run
    (workspace_id, diagnosis_id, recorded_at);
