-- V202: freeze pilot cohort membership when a Diagnosis is first created.
-- Existing rows deliberately remain NULL; declaring a pilot never rewrites history.

SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_troubleshooting_diagnosis'
      AND COLUMN_NAME = 'pilot_plan_version'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE mate_troubleshooting_diagnosis ADD COLUMN pilot_plan_version INT NULL',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_troubleshooting_diagnosis'
      AND INDEX_NAME = 'idx_ts_diagnosis_pilot'
);
SET @ddl := IF(@idx_exists = 0,
    'CREATE INDEX idx_ts_diagnosis_pilot ON mate_troubleshooting_diagnosis(workspace_id, pilot_plan_version, id)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
