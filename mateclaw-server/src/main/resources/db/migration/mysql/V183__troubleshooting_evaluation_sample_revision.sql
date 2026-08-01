-- V183: immutable recapture revisions for reproducible T8 evidence samples (MySQL 8).

ALTER TABLE mate_troubleshooting_evaluation_sample
    ADD COLUMN capture_identity_key VARCHAR(64) NULL AFTER sample_key,
    ADD COLUMN capture_revision INT NOT NULL DEFAULT 1 AFTER capture_identity_key;

UPDATE mate_troubleshooting_evaluation_sample
SET capture_identity_key = sample_key
WHERE capture_identity_key IS NULL;

ALTER TABLE mate_troubleshooting_evaluation_sample
    MODIFY COLUMN capture_identity_key VARCHAR(64) NOT NULL;

CREATE UNIQUE INDEX uk_ts_eval_capture_revision
    ON mate_troubleshooting_evaluation_sample(
        workspace_id, capture_identity_key, capture_revision);
