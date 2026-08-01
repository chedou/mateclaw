-- V176: immutable provider event-time boundary for intake session ownership.

ALTER TABLE mate_troubleshooting_intake_session
    ADD COLUMN reported_at TIMESTAMP(6) NULL;

UPDATE mate_troubleshooting_intake_session
SET reported_at = last_message_at
WHERE reported_at IS NULL;

CREATE INDEX idx_ts_intake_route_reported
    ON mate_troubleshooting_intake_session(workspace_id, routing_key, reported_at);
