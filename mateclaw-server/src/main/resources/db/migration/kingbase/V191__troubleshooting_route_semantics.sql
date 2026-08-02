-- V191: index the explicitly persisted route semantics without guessing from routeMode.

ALTER TABLE mate_troubleshooting_diagnosis
    ADD COLUMN IF NOT EXISTS investigation_mode VARCHAR(48);

ALTER TABLE mate_troubleshooting_diagnosis
    ADD COLUMN IF NOT EXISTS route_authority VARCHAR(48);

CREATE INDEX IF NOT EXISTS idx_ts_diagnosis_investigation
    ON mate_troubleshooting_diagnosis(workspace_id, investigation_mode, id);

CREATE INDEX IF NOT EXISTS idx_ts_diagnosis_authority
    ON mate_troubleshooting_diagnosis(workspace_id, route_authority, id);

UPDATE mate_troubleshooting_diagnosis
SET investigation_mode = NULLIF(
        aggregate_json::jsonb ->> 'investigationMode', ''),
    route_authority = NULLIF(
        aggregate_json::jsonb ->> 'routeAuthority', '')
WHERE contract_version NOT IN ('1.3', '1.4')
  AND (
      aggregate_json::jsonb ->> 'investigationMode' IS NOT NULL
      OR aggregate_json::jsonb ->> 'routeAuthority' IS NOT NULL
  );
