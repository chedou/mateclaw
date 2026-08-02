ALTER TABLE mate_troubleshooting_playbook_version
    ADD COLUMN knowledge_evidence_grade VARCHAR(32) NOT NULL DEFAULT 'UNVERIFIED';

-- Historical rows stay fail-closed. The application reconciler reconstructs the
-- original candidate and upgrades only an exact server-owned SHA-256 match.
