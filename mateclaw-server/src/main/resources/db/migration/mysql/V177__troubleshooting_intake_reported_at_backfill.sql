-- V177: repair V176 legacy backfill from the immutable aggregate boundary.
-- V176 may already have run in local/development databases, so correct every
-- existing row instead of editing an applied migration in place.

UPDATE mate_troubleshooting_intake_session
SET reported_at = CAST(
        REPLACE(
            REPLACE(
                JSON_UNQUOTE(JSON_EXTRACT(aggregate_json, '$.reportedAt')),
                'T', ' '),
            'Z', '')
        AS DATETIME(6));

ALTER TABLE mate_troubleshooting_intake_session
    MODIFY COLUMN reported_at TIMESTAMP(6) NOT NULL;
