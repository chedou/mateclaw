-- The dedicated troubleshooting robot is the product entrypoint for real
-- incidents. It remains read-only and fail-closed, but new conversations no
-- longer start in rehearsal mode.
UPDATE mate_agent
SET tags = 'troubleshooting,readonly,formal',
    update_time = NOW()
WHERE id = 1000000950
  AND workspace_id = 1
  AND name = 'troubleshooting-readonly-triage'
  AND deleted = 0
  AND (tags IS NULL OR tags = '' OR tags = 'troubleshooting,readonly,triage');
