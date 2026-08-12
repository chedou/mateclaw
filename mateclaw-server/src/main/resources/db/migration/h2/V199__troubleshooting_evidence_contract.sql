-- V190: workspace-managed evidence contracts (method library).
-- Query templates stay server-side; list APIs must not expose them to viewers.

CREATE TABLE IF NOT EXISTS mate_troubleshooting_evidence_contract (
    id                         BIGINT        NOT NULL PRIMARY KEY,
    workspace_id               BIGINT        NOT NULL,
    contract_ref               VARCHAR(128)  NOT NULL,
    signal_kind                VARCHAR(64)   NOT NULL,
    scope_type                 VARCHAR(32)   NOT NULL,
    system_name                VARCHAR(128),
    service_name               VARCHAR(192),
    scenario                   VARCHAR(256)  NOT NULL,
    question                   VARCHAR(512)  NOT NULL,
    summary                    VARCHAR(512)  NOT NULL,
    namespace                 VARCHAR(64)   NOT NULL,
    max_rows                   INT           NOT NULL DEFAULT 200,
    query_template             CLOB          NOT NULL,
    fixed_conditions_json      CLOB          NOT NULL,
    asset_parameters_json      CLOB          NOT NULL,
    field_aliases_json         CLOB          NOT NULL,
    enabled                    INT           NOT NULL DEFAULT 1,
    version                    INT           NOT NULL,
    changed_by                 VARCHAR(192)  NOT NULL,
    change_reason              VARCHAR(500)  NOT NULL,
    create_time                TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ts_evidence_contract_version
    ON mate_troubleshooting_evidence_contract(workspace_id, contract_ref, version);
CREATE INDEX IF NOT EXISTS idx_ts_evidence_contract_latest
    ON mate_troubleshooting_evidence_contract(workspace_id, contract_ref, version);
CREATE INDEX IF NOT EXISTS idx_ts_evidence_contract_scope
    ON mate_troubleshooting_evidence_contract(workspace_id, scope_type, system_name, service_name);
