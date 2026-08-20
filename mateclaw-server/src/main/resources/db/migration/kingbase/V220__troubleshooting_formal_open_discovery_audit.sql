-- V220: freeze formal generic-investigation authority in its immutable audit (KingbaseES).

ALTER TABLE mate_troubleshooting_open_discovery_run
    ADD COLUMN formal_pilot_plan_version INTEGER;
ALTER TABLE mate_troubleshooting_open_discovery_run
    ADD COLUMN source_acceptance_id VARCHAR(128);
ALTER TABLE mate_troubleshooting_open_discovery_run
    ADD COLUMN source_binding_fingerprint VARCHAR(64);
