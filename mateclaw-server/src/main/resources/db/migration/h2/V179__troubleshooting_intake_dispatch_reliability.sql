-- V179: separate stable Intake identity from transport delivery and make
-- fail-closed notification retries durable (H2).

ALTER TABLE mate_troubleshooting_intake_session
    ADD COLUMN delivery_conversation_id VARCHAR(384) NULL;

ALTER TABLE mate_troubleshooting_intake_investigation
    ADD COLUMN terminal_attempts INT NOT NULL DEFAULT 0;
