-- V218: immutable production-pilot provenance for Guance evaluation samples.
-- Historical rows remain rehearsal/unknown and therefore cannot enter the formal cohort.

ALTER TABLE mate_troubleshooting_evaluation_sample
    ADD COLUMN IF NOT EXISTS diagnosis_rehearsal BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE mate_troubleshooting_evaluation_sample
    ADD COLUMN IF NOT EXISTS pilot_plan_version INT NULL;
ALTER TABLE mate_troubleshooting_evaluation_sample
    ADD COLUMN IF NOT EXISTS source_playbook_id VARCHAR(128) NULL;
ALTER TABLE mate_troubleshooting_evaluation_sample
    ADD COLUMN IF NOT EXISTS source_playbook_version INT NULL;

CREATE INDEX IF NOT EXISTS idx_ts_eval_formal_pilot
    ON mate_troubleshooting_evaluation_sample(
        workspace_id, diagnosis_rehearsal, pilot_plan_version,
        source_platform, create_time);
