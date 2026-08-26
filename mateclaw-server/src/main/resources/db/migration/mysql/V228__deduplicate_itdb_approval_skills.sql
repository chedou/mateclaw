-- V228: Retire legacy/manual rows that use one of the four canonical ITDB
-- Skill names under a different ID. V227 introduced stable builtin IDs; keeping
-- two active rows with the same name breaks BuiltinSkillSeedService name lookup.

UPDATE mate_agent_skill
SET enabled = FALSE, deleted = 1, update_time = NOW()
WHERE deleted = 0
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
SET enabled = FALSE, deleted = 1, update_time = NOW()
WHERE deleted = 0
  AND name IN (
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
