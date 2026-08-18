-- V218: immutable production-pilot provenance for Guance evaluation samples.
-- Historical rows remain rehearsal/unknown and therefore cannot enter the formal cohort.

SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_troubleshooting_evaluation_sample'
      AND COLUMN_NAME = 'diagnosis_rehearsal'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE mate_troubleshooting_evaluation_sample ADD COLUMN diagnosis_rehearsal BOOLEAN NOT NULL DEFAULT TRUE',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_troubleshooting_evaluation_sample'
      AND COLUMN_NAME = 'pilot_plan_version'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE mate_troubleshooting_evaluation_sample ADD COLUMN pilot_plan_version INT NULL',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_troubleshooting_evaluation_sample'
      AND COLUMN_NAME = 'source_playbook_id'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE mate_troubleshooting_evaluation_sample ADD COLUMN source_playbook_id VARCHAR(128) NULL',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_troubleshooting_evaluation_sample'
      AND COLUMN_NAME = 'source_playbook_version'
);
SET @ddl := IF(@col_exists = 0,
    'ALTER TABLE mate_troubleshooting_evaluation_sample ADD COLUMN source_playbook_version INT NULL',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists := (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'mate_troubleshooting_evaluation_sample'
      AND INDEX_NAME = 'idx_ts_eval_formal_pilot'
);
SET @ddl := IF(@idx_exists = 0,
    'CREATE INDEX idx_ts_eval_formal_pilot ON mate_troubleshooting_evaluation_sample(workspace_id, diagnosis_rehearsal, pilot_plan_version, source_platform, create_time)',
    'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
