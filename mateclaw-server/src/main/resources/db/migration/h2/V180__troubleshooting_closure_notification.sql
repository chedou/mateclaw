-- V180: durable original-channel delivery after a Diagnosis is closed (H2).

ALTER TABLE mate_troubleshooting_diagnosis
    ADD COLUMN IF NOT EXISTS closure_notification_status VARCHAR(32)
        DEFAULT 'NOT_APPLICABLE' NOT NULL;
ALTER TABLE mate_troubleshooting_diagnosis
    ADD COLUMN IF NOT EXISTS closure_notification_attempts INT DEFAULT 0 NOT NULL;
ALTER TABLE mate_troubleshooting_diagnosis
    ADD COLUMN IF NOT EXISTS closure_notification_claimed_by VARCHAR(160) NULL;
ALTER TABLE mate_troubleshooting_diagnosis
    ADD COLUMN IF NOT EXISTS closure_notification_lease_expires_at TIMESTAMP(6) NULL;
ALTER TABLE mate_troubleshooting_diagnosis
    ADD COLUMN IF NOT EXISTS closure_notification_next_attempt_at TIMESTAMP(6) NULL;
ALTER TABLE mate_troubleshooting_diagnosis
    ADD COLUMN IF NOT EXISTS closure_notification_last_error VARCHAR(2000) NULL;
ALTER TABLE mate_troubleshooting_diagnosis
    ADD COLUMN IF NOT EXISTS closure_notification_completed_at TIMESTAMP(6) NULL;

CREATE INDEX IF NOT EXISTS idx_ts_diagnosis_closure_notify
    ON mate_troubleshooting_diagnosis(
        closure_notification_status,
        closure_notification_next_attempt_at,
        closure_notification_lease_expires_at);
