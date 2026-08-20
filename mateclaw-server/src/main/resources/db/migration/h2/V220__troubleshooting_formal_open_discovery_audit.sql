-- V220: freeze formal generic-investigation authority in its immutable audit (H2).

ALTER TABLE mate_troubleshooting_open_discovery_run
    ADD COLUMN IF NOT EXISTS formal_pilot_plan_version INT;
ALTER TABLE mate_troubleshooting_open_discovery_run
    ADD COLUMN IF NOT EXISTS source_acceptance_id VARCHAR(128);
ALTER TABLE mate_troubleshooting_open_discovery_run
    ADD COLUMN IF NOT EXISTS source_binding_fingerprint VARCHAR(64);
