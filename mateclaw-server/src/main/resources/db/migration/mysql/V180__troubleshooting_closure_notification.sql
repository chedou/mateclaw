-- V180: durable original-channel delivery after a Diagnosis is closed (MySQL 8).

ALTER TABLE mate_troubleshooting_diagnosis
    ADD COLUMN closure_notification_status VARCHAR(32)
        NOT NULL DEFAULT 'NOT_APPLICABLE',
    ADD COLUMN closure_notification_attempts INT NOT NULL DEFAULT 0,
    ADD COLUMN closure_notification_claimed_by VARCHAR(160) NULL,
    ADD COLUMN closure_notification_lease_expires_at TIMESTAMP(6) NULL,
    ADD COLUMN closure_notification_next_attempt_at TIMESTAMP(6) NULL,
    ADD COLUMN closure_notification_last_error VARCHAR(2000) NULL,
    ADD COLUMN closure_notification_completed_at TIMESTAMP(6) NULL;

CREATE INDEX idx_ts_diagnosis_closure_notify
    ON mate_troubleshooting_diagnosis(
        closure_notification_status,
        closure_notification_next_attempt_at,
        closure_notification_lease_expires_at);
