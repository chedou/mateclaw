-- V170: Workspace-scoped troubleshooting connector configuration.

CREATE TABLE IF NOT EXISTS mate_troubleshooting_connector_config (
    id                 BIGINT       NOT NULL PRIMARY KEY,
    workspace_id       BIGINT       NOT NULL,
    provider           VARCHAR(64)  NOT NULL,
    name               VARCHAR(128),
    base_url           VARCHAR(512),
    synthetics_path    VARCHAR(256),
    metrics_path       VARCHAR(256),
    token              MEDIUMTEXT,
    token_header       VARCHAR(128),
    token_prefix       VARCHAR(64),
    time_window        VARCHAR(128),
    synthetics_limit   INT,
    metrics_window     VARCHAR(128),
    metrics_limit      INT,
    max_response_chars INT,
    enabled            INT          NOT NULL DEFAULT 0,
    default_config     INT          NOT NULL DEFAULT 1,
    create_time        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    update_time        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    deleted            INT          NOT NULL DEFAULT 0,
    KEY idx_troubleshooting_connector_config_lookup (workspace_id, provider, enabled, deleted)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Workspace-scoped troubleshooting evidence connector configuration.';
