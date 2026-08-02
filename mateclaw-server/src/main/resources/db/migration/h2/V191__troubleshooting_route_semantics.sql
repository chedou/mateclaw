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
SET investigation_mode = CASE
        WHEN REGEXP_LIKE(aggregate_json, '"investigationMode"[[:space:]]*:[[:space:]]*"ERROR_CODE_PLAYBOOK"')
            THEN 'ERROR_CODE_PLAYBOOK'
        WHEN REGEXP_LIKE(aggregate_json, '"investigationMode"[[:space:]]*:[[:space:]]*"SCENARIO_PLAYBOOK"')
            THEN 'SCENARIO_PLAYBOOK'
        WHEN REGEXP_LIKE(aggregate_json, '"investigationMode"[[:space:]]*:[[:space:]]*"OPEN_DISCOVERY"')
            THEN 'OPEN_DISCOVERY'
        ELSE NULL
    END,
    route_authority = CASE
        WHEN REGEXP_LIKE(aggregate_json, '"routeAuthority"[[:space:]]*:[[:space:]]*"EXPLICIT"')
            THEN 'EXPLICIT'
        WHEN REGEXP_LIKE(aggregate_json, '"routeAuthority"[[:space:]]*:[[:space:]]*"RULE_MATCHED"')
            THEN 'RULE_MATCHED'
        WHEN REGEXP_LIKE(aggregate_json, '"routeAuthority"[[:space:]]*:[[:space:]]*"MODEL_PROPOSED"')
            THEN 'MODEL_PROPOSED'
        ELSE NULL
    END
WHERE contract_version NOT IN ('1.3', '1.4');
