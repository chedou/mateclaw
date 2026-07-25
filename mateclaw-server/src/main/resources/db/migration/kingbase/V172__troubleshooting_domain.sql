-- V172: deterministic troubleshooting domain (KingbaseES/PostgreSQL-compatible).
-- There is deliberately no production write-execution queue.

CREATE TABLE IF NOT EXISTS mate_troubleshooting_diagnosis (
    id               BIGINT       NOT NULL PRIMARY KEY,
    workspace_id     BIGINT       NOT NULL,
    diagnosis_id     VARCHAR(96)  NOT NULL,
    case_id          VARCHAR(96)  NOT NULL,
    run_id           VARCHAR(96)  NOT NULL,
    system           VARCHAR(96)  NOT NULL,
    error_code       VARCHAR(128),
    service          VARCHAR(192) NOT NULL,
    dedup_key        VARCHAR(64),
    rehearsal        BOOLEAN      NOT NULL DEFAULT FALSE,
    status           VARCHAR(48)  NOT NULL,
    contract_version VARCHAR(32)  NOT NULL,
    aggregate_json   TEXT         NOT NULL,
    version          INT          NOT NULL DEFAULT 0,
    deleted          INT          NOT NULL DEFAULT 0,
    create_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ts_diagnosis_id
    ON mate_troubleshooting_diagnosis(workspace_id, diagnosis_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ts_diagnosis_dedup
    ON mate_troubleshooting_diagnosis(workspace_id, dedup_key);
CREATE INDEX IF NOT EXISTS idx_ts_diagnosis_case
    ON mate_troubleshooting_diagnosis(workspace_id, case_id, create_time);

CREATE TABLE IF NOT EXISTS mate_troubleshooting_sop (
    id               BIGINT       NOT NULL PRIMARY KEY,
    workspace_id     BIGINT       NOT NULL,
    sop_id           VARCHAR(128) NOT NULL,
    route_key        VARCHAR(256) NOT NULL,
    system           VARCHAR(96)  NOT NULL,
    error_code       VARCHAR(128) NOT NULL,
    service          VARCHAR(192) NOT NULL,
    status           VARCHAR(32)  NOT NULL,
    verified         BOOLEAN      NOT NULL DEFAULT FALSE,
    contract_version VARCHAR(32)  NOT NULL,
    aggregate_json   TEXT         NOT NULL,
    version          INT          NOT NULL DEFAULT 0,
    deleted          INT          NOT NULL DEFAULT 0,
    create_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ts_sop_id
    ON mate_troubleshooting_sop(workspace_id, sop_id);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ts_sop_route
    ON mate_troubleshooting_sop(workspace_id, route_key);

CREATE TABLE IF NOT EXISTS mate_troubleshooting_knowledge_outbox (
    id               BIGINT       NOT NULL PRIMARY KEY,
    workspace_id     BIGINT       NOT NULL,
    publication_id   VARCHAR(160) NOT NULL,
    diagnosis_id     VARCHAR(96)  NOT NULL,
    candidate_id     VARCHAR(128) NOT NULL,
    event_type       VARCHAR(64)  NOT NULL,
    contract_version VARCHAR(48)  NOT NULL,
    payload_json     TEXT         NOT NULL,
    status           VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
    attempts         INT          NOT NULL DEFAULT 0,
    last_error       VARCHAR(2000),
    claimed_by       VARCHAR(192),
    lease_expires_at TIMESTAMP,
    published_at     TIMESTAMP,
    deleted          INT          NOT NULL DEFAULT 0,
    create_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_ts_outbox_publication
    ON mate_troubleshooting_knowledge_outbox(workspace_id, publication_id);
CREATE INDEX IF NOT EXISTS idx_ts_outbox_poll
    ON mate_troubleshooting_knowledge_outbox(status, lease_expires_at, create_time);
