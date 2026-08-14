-- Keep the cross-dialect persistence shape aligned with MySQL, where SYSTEM is
-- a reserved word.
ALTER TABLE mate_troubleshooting_diagnosis RENAME COLUMN system TO system_name;
ALTER TABLE mate_troubleshooting_sop RENAME COLUMN system TO system_name;
ALTER TABLE mate_troubleshooting_playbook_candidate RENAME COLUMN system TO system_name;
ALTER TABLE mate_troubleshooting_evaluation_sample RENAME COLUMN system TO system_name;
ALTER TABLE mate_troubleshooting_guance_acceptance RENAME COLUMN system TO system_name;
ALTER TABLE mate_troubleshooting_playbook_version RENAME COLUMN system TO system_name;
ALTER TABLE mate_troubleshooting_deployment_topology RENAME COLUMN system TO system_name;
ALTER TABLE mate_troubleshooting_evidence_route RENAME COLUMN system TO system_name;
ALTER TABLE mate_troubleshooting_observability_asset RENAME COLUMN system TO system_name;
ALTER TABLE mate_troubleshooting_evidence_contract_trial RENAME COLUMN system TO system_name;
