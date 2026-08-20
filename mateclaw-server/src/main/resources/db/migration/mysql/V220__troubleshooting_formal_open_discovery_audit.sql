-- V220: freeze formal generic-investigation authority in its immutable audit (MySQL 8).

ALTER TABLE mate_troubleshooting_open_discovery_run
    ADD COLUMN formal_pilot_plan_version INT NULL,
    ADD COLUMN source_acceptance_id VARCHAR(128) NULL,
    ADD COLUMN source_binding_fingerprint VARCHAR(64) NULL;
