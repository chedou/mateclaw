-- V190: workspace-managed evidence contracts (method library).
-- Query templates stay server-side; list APIs must not expose them to viewers.

CREATE TABLE IF NOT EXISTS mate_troubleshooting_evidence_contract (
    id                         BIGINT        NOT NULL PRIMARY KEY,
    workspace_id               BIGINT        NOT NULL,
    contract_ref               VARCHAR(128)  NOT NULL,
    signal_kind                VARCHAR(64)   NOT NULL,
    scope_type                 VARCHAR(32)   NOT NULL,
    system_name                VARCHAR(128)  NULL,
    service_name               VARCHAR(192)  NULL,
    scenario                   VARCHAR(256)  NOT NULL,
    question                   VARCHAR(512)  NOT NULL,
    summary                    VARCHAR(512)  NOT NULL,
    namespace                 VARCHAR(64)   NOT NULL,
    max_rows                   INT           NOT NULL DEFAULT 200,
    query_template             LONGTEXT      NOT NULL,
    fixed_conditions_json      LONGTEXT      NOT NULL,
    asset_parameters_json      LONGTEXT      NOT NULL,
    field_aliases_json         LONGTEXT      NOT NULL,
    enabled                    INT           NOT NULL DEFAULT 1,
    version                    INT           NOT NULL,
    changed_by                 VARCHAR(192)  NOT NULL,
    change_reason              VARCHAR(500)  NOT NULL,
    create_time                TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ts_evidence_contract_version (workspace_id, contract_ref, version),
    KEY idx_ts_evidence_contract_latest (workspace_id, contract_ref, version),
    KEY idx_ts_evidence_contract_scope (workspace_id, scope_type, system_name, service_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
