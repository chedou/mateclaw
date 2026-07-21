-- V169: Configurable observability query templates for troubleshooting evidence.

CREATE TABLE IF NOT EXISTS mate_troubleshooting_query_template (
    id               BIGINT       NOT NULL PRIMARY KEY,
    workspace_id     BIGINT       NOT NULL,
    provider         VARCHAR(64)  NOT NULL,
    evidence_type    VARCHAR(64)  NOT NULL,
    template_key     VARCHAR(128) NOT NULL,
    name             VARCHAR(128) NOT NULL,
    description      CLOB,
    payload_template CLOB         NOT NULL,
    dql_template     CLOB,
    match_json       CLOB,
    enabled          INT          NOT NULL DEFAULT 1,
    default_template INT          NOT NULL DEFAULT 0,
    priority         INT          NOT NULL DEFAULT 0,
    create_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted          INT          NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS idx_troubleshooting_query_template_lookup
    ON mate_troubleshooting_query_template (workspace_id, provider, evidence_type, template_key, enabled, deleted);

CREATE INDEX IF NOT EXISTS idx_troubleshooting_query_template_default
    ON mate_troubleshooting_query_template (workspace_id, provider, evidence_type, default_template, enabled, priority, deleted);
