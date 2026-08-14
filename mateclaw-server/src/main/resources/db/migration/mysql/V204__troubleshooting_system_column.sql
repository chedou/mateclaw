-- `SYSTEM` is reserved by MySQL 8. Rename every troubleshooting scope column
-- after the historical migrations have finished so runtime SQL never relies on
-- identifier quoting. CHANGE COLUMN is supported by MySQL 5.7 and 8.0.

ALTER TABLE mate_troubleshooting_diagnosis
    CHANGE COLUMN `system` system_name VARCHAR(96) NOT NULL;
ALTER TABLE mate_troubleshooting_sop
    CHANGE COLUMN `system` system_name VARCHAR(96) NOT NULL;
ALTER TABLE mate_troubleshooting_playbook_candidate
    CHANGE COLUMN `system` system_name VARCHAR(96) NOT NULL;
ALTER TABLE mate_troubleshooting_evaluation_sample
    CHANGE COLUMN `system` system_name VARCHAR(96) NOT NULL;
ALTER TABLE mate_troubleshooting_guance_acceptance
    CHANGE COLUMN `system` system_name VARCHAR(96) NOT NULL;
ALTER TABLE mate_troubleshooting_playbook_version
    CHANGE COLUMN `system` system_name VARCHAR(96) NOT NULL;
ALTER TABLE mate_troubleshooting_deployment_topology
    CHANGE COLUMN `system` system_name VARCHAR(128) NOT NULL;
ALTER TABLE mate_troubleshooting_evidence_route
    CHANGE COLUMN `system` system_name VARCHAR(128) NOT NULL;
ALTER TABLE mate_troubleshooting_observability_asset
    CHANGE COLUMN `system` system_name VARCHAR(128) NOT NULL;
ALTER TABLE mate_troubleshooting_evidence_contract_trial
    CHANGE COLUMN `system` system_name VARCHAR(128) NOT NULL;
