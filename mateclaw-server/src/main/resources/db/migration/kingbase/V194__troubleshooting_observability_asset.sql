-- V194: immutable workspace observability-asset revisions.
-- Contains reviewed contract references and bounded resource identifiers only.

CREATE TABLE IF NOT EXISTS mate_troubleshooting_observability_asset (
    id                 BIGINT       NOT NULL PRIMARY KEY,
    workspace_id       BIGINT       NOT NULL,
    system             VARCHAR(128) NOT NULL,
    service            VARCHAR(128) NOT NULL,
    display_name       VARCHAR(160) NOT NULL,
    platform           VARCHAR(64)  NOT NULL,
    environment        VARCHAR(256) NOT NULL,
    region             VARCHAR(256) NULL,
    cluster_name       VARCHAR(256) NULL,
    namespace_name     VARCHAR(256) NULL,
    enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
    signal_bindings    CLOB         NOT NULL,
    asset_parameters   CLOB         NOT NULL,
    version            INT          NOT NULL,
    changed_by         VARCHAR(128) NOT NULL,
    change_reason      VARCHAR(500) NOT NULL,
    create_time        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ts_observability_asset_revision
    ON mate_troubleshooting_observability_asset(workspace_id, system, service, version);
CREATE INDEX IF NOT EXISTS idx_ts_observability_asset_scope
    ON mate_troubleshooting_observability_asset(workspace_id, system, service);
