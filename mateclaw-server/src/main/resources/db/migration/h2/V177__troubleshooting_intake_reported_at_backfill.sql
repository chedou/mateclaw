-- V177: repair V176 legacy backfill from the immutable aggregate boundary.
-- V176 may already have run in local/development databases, so correct every
-- existing row instead of editing an applied migration in place.

UPDATE mate_troubleshooting_intake_session
SET reported_at = CAST(
        REPLACE(
            REPLACE(
                REGEXP_REPLACE(
                    aggregate_json,
                    '(?s).*"reportedAt"\s*:\s*"([^"]+)".*',
                    '\1'),
                'T', ' '),
            'Z', '')
        AS TIMESTAMP(6));

ALTER TABLE mate_troubleshooting_intake_session
    ALTER COLUMN reported_at TIMESTAMP(6) NOT NULL;
