-- V202: freeze pilot cohort membership when a Diagnosis is first created.
-- Existing rows deliberately remain NULL; declaring a pilot never rewrites history.

ALTER TABLE mate_troubleshooting_diagnosis
    ADD COLUMN IF NOT EXISTS pilot_plan_version INT NULL;

CREATE INDEX IF NOT EXISTS idx_ts_diagnosis_pilot
    ON mate_troubleshooting_diagnosis(workspace_id, pilot_plan_version, id);
