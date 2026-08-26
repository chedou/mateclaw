-- Keep the built-in troubleshooting robot aligned with the formal read-only
-- product entrypoint in local and test environments.
UPDATE mate_agent
SET tags = 'troubleshooting,readonly,formal',
    update_time = NOW()
WHERE id = 1000000950
  AND workspace_id = 1
  AND name = 'troubleshooting-readonly-triage'
  AND deleted = 0
  AND (tags IS NULL OR tags = '' OR tags = 'troubleshooting,readonly,triage');
