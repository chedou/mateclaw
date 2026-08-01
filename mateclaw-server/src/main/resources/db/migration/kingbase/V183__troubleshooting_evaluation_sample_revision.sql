-- V183: immutable recapture revisions for reproducible T8 evidence samples (Kingbase).

ALTER TABLE mate_troubleshooting_evaluation_sample
    ADD COLUMN IF NOT EXISTS capture_identity_key VARCHAR(64);
ALTER TABLE mate_troubleshooting_evaluation_sample
    ADD COLUMN IF NOT EXISTS capture_revision INT NOT NULL DEFAULT 1;

UPDATE mate_troubleshooting_evaluation_sample
SET capture_identity_key = sample_key
WHERE capture_identity_key IS NULL;

ALTER TABLE mate_troubleshooting_evaluation_sample
    ALTER COLUMN capture_identity_key SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_ts_eval_capture_revision
    ON mate_troubleshooting_evaluation_sample(
        workspace_id, capture_identity_key, capture_revision);
