-- V195: immutable, secret-free audit rows for admin read-only contract trials.

CREATE TABLE IF NOT EXISTS mate_troubleshooting_evidence_contract_trial (
    id                 BIGINT       NOT NULL PRIMARY KEY,
    trial_id           VARCHAR(64)  NOT NULL,
    workspace_id       BIGINT       NOT NULL,
    system             VARCHAR(128) NOT NULL,
    service            VARCHAR(128) NOT NULL,
    contract_ref       VARCHAR(128) NOT NULL,
    signal_kind        VARCHAR(128) NOT NULL,
    asset_id           VARCHAR(128) NOT NULL,
    asset_version      INT          NOT NULL,
    status             VARCHAR(32)  NOT NULL,
    stop_reason        VARCHAR(64)  NOT NULL,
    source_platform    VARCHAR(64)  NOT NULL,
    observed_fields    TEXT         NOT NULL,
    duration_ms        BIGINT       NOT NULL,
    actor              VARCHAR(128) NOT NULL,
    completed_at       TIMESTAMP    NOT NULL,
    create_time        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ts_evidence_contract_trial_id (trial_id),
    KEY idx_ts_evidence_contract_trial_scope
        (workspace_id, system, service, contract_ref, completed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
