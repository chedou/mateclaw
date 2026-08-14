-- V194: immutable workspace observability-asset revisions.
--
-- This table connects a business module to reviewed query contract references and
-- bounded source-side identifiers. It deliberately has no endpoint, credential,
-- raw DQL, raw log row, or model-authored selector column.

CREATE TABLE IF NOT EXISTS mate_troubleshooting_observability_asset (
    id                 BIGINT       NOT NULL PRIMARY KEY,
    workspace_id       BIGINT       NOT NULL,
    `system`           VARCHAR(128) NOT NULL,
    service            VARCHAR(128) NOT NULL,
    display_name       VARCHAR(160) NOT NULL,
    platform           VARCHAR(64)  NOT NULL,
    environment        VARCHAR(256) NOT NULL,
    region             VARCHAR(256) NULL,
    cluster_name       VARCHAR(256) NULL,
    namespace_name     VARCHAR(256) NULL,
    enabled            TINYINT(1)   NOT NULL DEFAULT 1,
    signal_bindings    TEXT         NOT NULL,
    asset_parameters   TEXT         NOT NULL,
    version            INT          NOT NULL,
    changed_by         VARCHAR(128) NOT NULL,
    change_reason      VARCHAR(500) NOT NULL,
    create_time        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ts_observability_asset_revision
        (workspace_id, `system`, service, version),
    KEY idx_ts_observability_asset_scope
        (workspace_id, `system`, service)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
