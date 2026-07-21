-- V169: Configurable observability query templates for troubleshooting evidence.

CREATE TABLE IF NOT EXISTS mate_troubleshooting_query_template (
    id               BIGINT       NOT NULL PRIMARY KEY,
    workspace_id     BIGINT       NOT NULL,
    provider         VARCHAR(64)  NOT NULL,
    evidence_type    VARCHAR(64)  NOT NULL,
    template_key     VARCHAR(128) NOT NULL,
    name             VARCHAR(128) NOT NULL,
    description      MEDIUMTEXT,
    payload_template MEDIUMTEXT   NOT NULL,
    dql_template     MEDIUMTEXT,
    match_json       MEDIUMTEXT,
    enabled          INT          NOT NULL DEFAULT 1,
    default_template INT          NOT NULL DEFAULT 0,
    priority         INT          NOT NULL DEFAULT 0,
    create_time      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted          INT          NOT NULL DEFAULT 0,
    KEY idx_troubleshooting_query_template_lookup (workspace_id, provider, evidence_type, template_key, enabled, deleted),
    KEY idx_troubleshooting_query_template_default (workspace_id, provider, evidence_type, default_template, enabled, priority, deleted)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Configurable observability query templates used by troubleshooting evidence connectors.';
