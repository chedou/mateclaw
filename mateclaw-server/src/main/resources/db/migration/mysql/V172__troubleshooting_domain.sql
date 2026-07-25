-- V172: deterministic troubleshooting domain (MySQL 8).
-- There is deliberately no production write-execution queue.

CREATE TABLE IF NOT EXISTS mate_troubleshooting_diagnosis (
    id               BIGINT       NOT NULL PRIMARY KEY,
    workspace_id     BIGINT       NOT NULL,
    diagnosis_id     VARCHAR(96)  NOT NULL,
    case_id          VARCHAR(96)  NOT NULL,
    run_id           VARCHAR(96)  NOT NULL,
    system           VARCHAR(96)  NOT NULL,
    error_code       VARCHAR(128) NULL,
    service          VARCHAR(192) NOT NULL,
    dedup_key        VARCHAR(64)  NULL,
    rehearsal        BOOLEAN      NOT NULL DEFAULT FALSE,
    status           VARCHAR(48)  NOT NULL,
    contract_version VARCHAR(32)  NOT NULL,
    aggregate_json   LONGTEXT     NOT NULL,
    version          INT          NOT NULL DEFAULT 0,
    deleted          INT          NOT NULL DEFAULT 0,
    create_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ts_diagnosis_id (workspace_id, diagnosis_id),
    UNIQUE KEY uk_ts_diagnosis_dedup (workspace_id, dedup_key),
    KEY idx_ts_diagnosis_case (workspace_id, case_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
    aggregate_json   LONGTEXT     NOT NULL,
    version          INT          NOT NULL DEFAULT 0,
    deleted          INT          NOT NULL DEFAULT 0,
    create_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ts_sop_id (workspace_id, sop_id),
    UNIQUE KEY uk_ts_sop_route (workspace_id, route_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS mate_troubleshooting_knowledge_outbox (
    id               BIGINT       NOT NULL PRIMARY KEY,
    workspace_id     BIGINT       NOT NULL,
    publication_id   VARCHAR(160) NOT NULL,
    diagnosis_id     VARCHAR(96)  NOT NULL,
    candidate_id     VARCHAR(128) NOT NULL,
    event_type       VARCHAR(64)  NOT NULL,
    contract_version VARCHAR(48)  NOT NULL,
    payload_json     LONGTEXT     NOT NULL,
    status           VARCHAR(24)  NOT NULL DEFAULT 'PENDING',
    attempts         INT          NOT NULL DEFAULT 0,
    last_error       VARCHAR(2000) NULL,
    claimed_by       VARCHAR(192) NULL,
    lease_expires_at TIMESTAMP    NULL,
    published_at     TIMESTAMP    NULL,
    deleted          INT          NOT NULL DEFAULT 0,
    create_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_ts_outbox_publication (workspace_id, publication_id),
    KEY idx_ts_outbox_poll (status, lease_expires_at, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
