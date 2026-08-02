-- V191: index the explicitly persisted route semantics without guessing from routeMode.

SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_troubleshooting_diagnosis'
      AND COLUMN_NAME = 'investigation_mode'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE mate_troubleshooting_diagnosis ADD COLUMN investigation_mode VARCHAR(48) NULL',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_troubleshooting_diagnosis'
      AND COLUMN_NAME = 'route_authority'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE mate_troubleshooting_diagnosis ADD COLUMN route_authority VARCHAR(48) NULL',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_troubleshooting_diagnosis'
      AND INDEX_NAME = 'idx_ts_diagnosis_investigation'
);
SET @ddl := IF(@idx_exists = 0,
    'CREATE INDEX idx_ts_diagnosis_investigation ON mate_troubleshooting_diagnosis(workspace_id, investigation_mode, id)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_troubleshooting_diagnosis'
      AND INDEX_NAME = 'idx_ts_diagnosis_authority'
);
SET @ddl := IF(@idx_exists = 0,
    'CREATE INDEX idx_ts_diagnosis_authority ON mate_troubleshooting_diagnosis(workspace_id, route_authority, id)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

UPDATE mate_troubleshooting_diagnosis
SET investigation_mode = CASE
        WHEN JSON_EXTRACT(aggregate_json, '$.investigationMode') IS NULL THEN NULL
        WHEN JSON_TYPE(JSON_EXTRACT(aggregate_json, '$.investigationMode')) = 'NULL' THEN NULL
        WHEN JSON_UNQUOTE(JSON_EXTRACT(aggregate_json, '$.investigationMode')) = '' THEN NULL
        WHEN JSON_UNQUOTE(JSON_EXTRACT(aggregate_json, '$.investigationMode')) = 'ERROR_CODE_PLAYBOOK'
            THEN 'ERROR_CODE_PLAYBOOK'
        WHEN JSON_UNQUOTE(JSON_EXTRACT(aggregate_json, '$.investigationMode')) = 'SCENARIO_PLAYBOOK'
            THEN 'SCENARIO_PLAYBOOK'
        WHEN JSON_UNQUOTE(JSON_EXTRACT(aggregate_json, '$.investigationMode')) = 'OPEN_DISCOVERY'
            THEN 'OPEN_DISCOVERY'
        ELSE NULL
    END,
    route_authority = CASE
        WHEN JSON_EXTRACT(aggregate_json, '$.routeAuthority') IS NULL THEN NULL
        WHEN JSON_TYPE(JSON_EXTRACT(aggregate_json, '$.routeAuthority')) = 'NULL' THEN NULL
        WHEN JSON_UNQUOTE(JSON_EXTRACT(aggregate_json, '$.routeAuthority')) = '' THEN NULL
        WHEN JSON_UNQUOTE(JSON_EXTRACT(aggregate_json, '$.routeAuthority')) = 'EXPLICIT'
            THEN 'EXPLICIT'
        WHEN JSON_UNQUOTE(JSON_EXTRACT(aggregate_json, '$.routeAuthority')) = 'RULE_MATCHED'
            THEN 'RULE_MATCHED'
        WHEN JSON_UNQUOTE(JSON_EXTRACT(aggregate_json, '$.routeAuthority')) = 'MODEL_PROPOSED'
            THEN 'MODEL_PROPOSED'
        ELSE NULL
    END
WHERE contract_version NOT IN ('1.3', '1.4')
  AND (
      JSON_EXTRACT(aggregate_json, '$.investigationMode') IS NOT NULL
      OR JSON_EXTRACT(aggregate_json, '$.routeAuthority') IS NOT NULL
  );
