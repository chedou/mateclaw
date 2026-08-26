-- V227: Register the four bundled SXF ITDB approval skills before binding them.
-- SKILL.md under classpath:skills/* remains the canonical content and is synced
-- by BuiltinSkillSeedService after Flyway. Approval never authorizes SQL execution.

MERGE INTO mate_skill
    (id, name, description, skill_type, icon, version, author, config_json,
     enabled, builtin, tags, workspace_id, create_time, update_time, deleted)
KEY (id)
VALUES
    (2091870934831804418, 'sxf-itdb-sql-approval-reader',
     'Read fresh ITDB ticket and pending-queue evidence without approving or executing SQL.',
     'builtin', '📖', '1.0.0', 'Sangfor',
     '{"upstream":"mateclaw","entryFile":"SKILL.md"}',
     TRUE, TRUE, 'SXF,ITDB,SQL,approval,read-only', 1, NOW(), NOW(), 0),
    (2091870991194861569, 'sxf-itdb-sql-risk-assessor',
     'Assess complete ITDB SQL with deterministic blockers, rollback, lock, and semantic risk checks.',
     'builtin', '🔍', '1.0.0', 'Sangfor',
     '{"upstream":"mateclaw","entryFile":"SKILL.md"}',
     TRUE, TRUE, 'SXF,ITDB,SQL,risk,safety', 1, NOW(), NOW(), 0),
    (2091871049277583362, 'sxf-itdb-sql-execution-decision',
     'Produce AUTO_APPROVABLE, MANUAL_REVIEW, or REJECT while keeping SQL execution forbidden.',
     'builtin', '⚖️', '1.0.0', 'Sangfor',
     '{"upstream":"mateclaw","entryFile":"SKILL.md"}',
     TRUE, TRUE, 'SXF,ITDB,SQL,decision,safety', 1, NOW(), NOW(), 0),
    (2091871106345283585, 'sxf-itdb-sql-approval-submit',
     'Submit one freshly revalidated ITDB approval after explicit per-ticket confirmation; never execute SQL.',
     'builtin', '✅', '1.0.0', 'Sangfor',
     '{"upstream":"mateclaw","entryFile":"SKILL.md"}',
     TRUE, TRUE, 'SXF,ITDB,SQL,approval,confirmation', 1, NOW(), NOW(), 0);

MERGE INTO mate_agent_skill
    (id, agent_id, skill_id, enabled, config_json, create_time, update_time, deleted)
KEY (agent_id, skill_id)
SELECT 1000000961, a.id, s.id, TRUE, NULL, NOW(), NOW(), 0
FROM mate_agent a, mate_skill s
WHERE a.workspace_id = 1 AND a.name = 'SXF-ITDB SQL审批安全官'
  AND s.id = 2091870934831804418 AND s.deleted = 0;

MERGE INTO mate_agent_skill
    (id, agent_id, skill_id, enabled, config_json, create_time, update_time, deleted)
KEY (agent_id, skill_id)
SELECT 1000000962, a.id, s.id, TRUE, NULL, NOW(), NOW(), 0
FROM mate_agent a, mate_skill s
WHERE a.workspace_id = 1 AND a.name = 'SXF-ITDB SQL审批安全官'
  AND s.id = 2091870991194861569 AND s.deleted = 0;

MERGE INTO mate_agent_skill
    (id, agent_id, skill_id, enabled, config_json, create_time, update_time, deleted)
KEY (agent_id, skill_id)
SELECT 1000000963, a.id, s.id, TRUE, NULL, NOW(), NOW(), 0
FROM mate_agent a, mate_skill s
WHERE a.workspace_id = 1 AND a.name = 'SXF-ITDB SQL审批安全官'
  AND s.id = 2091871049277583362 AND s.deleted = 0;

MERGE INTO mate_agent_skill
    (id, agent_id, skill_id, enabled, config_json, create_time, update_time, deleted)
KEY (agent_id, skill_id)
SELECT 1000000964, a.id, s.id, TRUE, NULL, NOW(), NOW(), 0
FROM mate_agent a, mate_skill s
WHERE a.workspace_id = 1 AND a.name = 'SXF-ITDB SQL审批安全官'
  AND s.id = 2091871106345283585 AND s.deleted = 0;
