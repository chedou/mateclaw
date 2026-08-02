-- V191: index the explicitly persisted route semantics without guessing from routeMode.

ALTER TABLE mate_troubleshooting_diagnosis
    ADD COLUMN IF NOT EXISTS investigation_mode VARCHAR(48) NULL;

ALTER TABLE mate_troubleshooting_diagnosis
    ADD COLUMN IF NOT EXISTS route_authority VARCHAR(48) NULL;

CREATE INDEX idx_ts_diagnosis_investigation
    ON mate_troubleshooting_diagnosis(workspace_id, investigation_mode, id);

CREATE INDEX idx_ts_diagnosis_authority
    ON mate_troubleshooting_diagnosis(workspace_id, route_authority, id);

UPDATE mate_troubleshooting_diagnosis
SET investigation_mode = CASE
        WHEN JSON_EXTRACT(aggregate_json, '$.investigationMode') IS NULL THEN NULL
        WHEN JSON_TYPE(JSON_EXTRACT(aggregate_json, '$.investigationMode')) = 'NULL' THEN NULL
        WHEN JSON_UNQUOTE(JSON_EXTRACT(aggregate_json, '$.investigationMode')) = '' THEN NULL
        ELSE JSON_UNQUOTE(JSON_EXTRACT(aggregate_json, '$.investigationMode'))
    END,
    route_authority = CASE
        WHEN JSON_EXTRACT(aggregate_json, '$.routeAuthority') IS NULL THEN NULL
        WHEN JSON_TYPE(JSON_EXTRACT(aggregate_json, '$.routeAuthority')) = 'NULL' THEN NULL
        WHEN JSON_UNQUOTE(JSON_EXTRACT(aggregate_json, '$.routeAuthority')) = '' THEN NULL
        ELSE JSON_UNQUOTE(JSON_EXTRACT(aggregate_json, '$.routeAuthority'))
    END
WHERE contract_version NOT IN ('1.3', '1.4')
  AND (
      JSON_EXTRACT(aggregate_json, '$.investigationMode') IS NOT NULL
      OR JSON_EXTRACT(aggregate_json, '$.routeAuthority') IS NOT NULL
  );
