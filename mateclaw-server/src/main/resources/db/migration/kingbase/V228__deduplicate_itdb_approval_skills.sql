-- V228: Quarantine legacy/manual rows that use one of the four canonical ITDB
-- Skill names under a different ID. V227 introduced stable builtin IDs; keeping
-- two rows with the same exact name breaks startup lookup even when one is soft
-- deleted. Renaming preserves unrelated legacy bindings and administrator state;
-- only the dedicated approval employee is moved exclusively to canonical rows.

UPDATE mate_agent_skill
SET enabled = FALSE, deleted = 1, update_time = NOW()
WHERE agent_id IN (
    SELECT id FROM mate_agent
    WHERE workspace_id = 1 AND name = 'SXF-ITDB SQL审批安全官'
  )
  AND skill_id IN (
    SELECT id FROM mate_skill
    WHERE name IN (
      'sxf-itdb-sql-approval-reader',
      'sxf-itdb-sql-risk-assessor',
      'sxf-itdb-sql-execution-decision',
      'sxf-itdb-sql-approval-submit'
    )
    AND id NOT IN (
      2091870934831804418,
      2091870991194861569,
      2091871049277583362,
      2091871106345283585
    )
  );

UPDATE mate_skill
SET name = name || '-legacy-' || CAST(id AS VARCHAR), update_time = NOW()
WHERE name IN (
    'sxf-itdb-sql-approval-reader',
    'sxf-itdb-sql-risk-assessor',
    'sxf-itdb-sql-execution-decision',
    'sxf-itdb-sql-approval-submit'
  )
  AND id NOT IN (
    2091870934831804418,
    2091870991194861569,
    2091871049277583362,
    2091871106345283585
  );
