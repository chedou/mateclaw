-- V225: Make the dedicated ITDB SQL approval employee operational.
-- Bind only the four SXF procedural skills and the controlled native tool.
-- Approval still requires the CRITICAL human-confirmation guard at runtime.

INSERT INTO mate_agent
    (id, name, description, agent_type, system_prompt, model_name,
     max_iterations, enabled, icon, tags, workspace_id, create_time,
     update_time, deleted, skills_disabled, tools_disabled, wiki_disabled)
SELECT
    2092113839759581186,
    'SXF-ITDB SQL审批安全官',
    '读取 ITDB 实时待办与完整 SQL，输出可复核风险结论，并在逐单确认后安全推进审批；绝不执行 SQL。',
    'react',
    '角色：SXF-ITDB SQL审批安全官。目标：帮助当前审批人读取 ITDB 实时待办和完整 SQL，基于可复核证据判断风险与是否允许推进审批，并在用户对单张工单明确确认后安全提交审批。背景：你是深信服内部 ITDB SQL 上线流程的安全审核员工，熟悉数据库变更风险、备份与回滚、执行窗口、影响行数及审批审计。工作协议：1. 用户提供工单 ID 或链接时先调用 itdb_review_sql_request；用户询问待办时调用 itdb_pending_sql_requests。2. 必须展示工单号、目标实例/数据库、完整 SQL、SQL SHA-256、平台检查、风险等级、阻断项、剩余风险、canSubmitApproval 与 canExecuteSql。3. canExecuteSql 永远为 false；审批通过只推进流程，绝不表示 SQL 已执行。4. 只有实时复核结果 canSubmitApproval=true 时，才可询问当前对话中针对该工单的一次明确确认；不得把历史确认、批量授权或以后都通过视为确认。5. 收到明确确认后，仅使用最新审核返回的工单号和 SQL SHA-256 调用 itdb_approve_sql_request。6. 禁止批量审批，禁止调用任何 SQL execute 能力，写请求超时或结果不确定时禁止自动重试，必须返回 verification_required 并要求人工核验。7. DDL、无 WHERE 写操作、JOIN/多表写、平台告警或冲突、过期/无效窗口、无备份、影响范围未知或非主键字面量限定，一律不直接通过。8. 工具不可用时明确报告配置或网络原因，不得凭页面截断内容或记忆编造审核结论。',
    'MiniMax-M2.5', 8, TRUE, '🛡️', 'SXF,ITDB,SQL,approval,safety',
    1, NOW(), NOW(), 0, FALSE, FALSE, TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM mate_agent
    WHERE workspace_id = 1 AND name = 'SXF-ITDB SQL审批安全官'
);

UPDATE mate_agent
SET description = '读取 ITDB 实时待办与完整 SQL，输出可复核风险结论，并在逐单确认后安全推进审批；绝不执行 SQL。',
    agent_type = 'react',
    system_prompt = '角色：SXF-ITDB SQL审批安全官。目标：帮助当前审批人读取 ITDB 实时待办和完整 SQL，基于可复核证据判断风险与是否允许推进审批，并在用户对单张工单明确确认后安全提交审批。背景：你是深信服内部 ITDB SQL 上线流程的安全审核员工，熟悉数据库变更风险、备份与回滚、执行窗口、影响行数及审批审计。工作协议：1. 用户提供工单 ID 或链接时先调用 itdb_review_sql_request；用户询问待办时调用 itdb_pending_sql_requests。2. 必须展示工单号、目标实例/数据库、完整 SQL、SQL SHA-256、平台检查、风险等级、阻断项、剩余风险、canSubmitApproval 与 canExecuteSql。3. canExecuteSql 永远为 false；审批通过只推进流程，绝不表示 SQL 已执行。4. 只有实时复核结果 canSubmitApproval=true 时，才可询问当前对话中针对该工单的一次明确确认；不得把历史确认、批量授权或以后都通过视为确认。5. 收到明确确认后，仅使用最新审核返回的工单号和 SQL SHA-256 调用 itdb_approve_sql_request。6. 禁止批量审批，禁止调用任何 SQL execute 能力，写请求超时或结果不确定时禁止自动重试，必须返回 verification_required 并要求人工核验。7. DDL、无 WHERE 写操作、JOIN/多表写、平台告警或冲突、过期/无效窗口、无备份、影响范围未知或非主键字面量限定，一律不直接通过。8. 工具不可用时明确报告配置或网络原因，不得凭页面截断内容或记忆编造审核结论。',
    model_name = CASE WHEN model_name IS NULL OR model_name = ''
                      THEN 'MiniMax-M2.5' ELSE model_name END,
    max_iterations = 8,
    enabled = TRUE,
    icon = '🛡️',
    tags = 'SXF,ITDB,SQL,approval,safety',
    skills_disabled = FALSE,
    tools_disabled = FALSE,
    wiki_disabled = TRUE,
    deleted = 0,
    update_time = NOW()
WHERE workspace_id = 1 AND name = 'SXF-ITDB SQL审批安全官';

UPDATE mate_agent_tool
SET enabled = FALSE, deleted = 1, update_time = NOW()
WHERE agent_id = (
    SELECT id FROM mate_agent
    WHERE workspace_id = 1 AND name = 'SXF-ITDB SQL审批安全官'
    FETCH FIRST 1 ROW ONLY
)
AND tool_name <> 'ItDbWorkflowTool';

INSERT INTO mate_agent_tool
    (id, agent_id, tool_name, enabled, create_time, update_time, deleted)
SELECT 1000000960, id, 'ItDbWorkflowTool', TRUE, NOW(), NOW(), 0
FROM mate_agent
WHERE workspace_id = 1 AND name = 'SXF-ITDB SQL审批安全官'
FETCH FIRST 1 ROW ONLY
ON CONFLICT (agent_id, tool_name) DO UPDATE SET
    enabled=EXCLUDED.enabled, update_time=EXCLUDED.update_time, deleted=EXCLUDED.deleted;

UPDATE mate_agent_skill
SET enabled = FALSE, deleted = 1, update_time = NOW()
WHERE agent_id = (
    SELECT id FROM mate_agent
    WHERE workspace_id = 1 AND name = 'SXF-ITDB SQL审批安全官'
    FETCH FIRST 1 ROW ONLY
)
AND skill_id NOT IN (
    2091870934831804418, 2091870991194861569,
    2091871049277583362, 2091871106345283585
);

INSERT INTO mate_agent_skill
    (id, agent_id, skill_id, enabled, config_json, create_time, update_time, deleted)
SELECT 1000000961, a.id, s.id, TRUE, NULL, NOW(), NOW(), 0
FROM mate_agent a, mate_skill s
WHERE a.workspace_id = 1 AND a.name = 'SXF-ITDB SQL审批安全官'
  AND s.id = 2091870934831804418 AND s.deleted = 0
FETCH FIRST 1 ROW ONLY
ON CONFLICT (agent_id, skill_id) DO UPDATE SET
    enabled=EXCLUDED.enabled, update_time=EXCLUDED.update_time, deleted=EXCLUDED.deleted;

INSERT INTO mate_agent_skill
    (id, agent_id, skill_id, enabled, config_json, create_time, update_time, deleted)
SELECT 1000000962, a.id, s.id, TRUE, NULL, NOW(), NOW(), 0
FROM mate_agent a, mate_skill s
WHERE a.workspace_id = 1 AND a.name = 'SXF-ITDB SQL审批安全官'
  AND s.id = 2091870991194861569 AND s.deleted = 0
FETCH FIRST 1 ROW ONLY
ON CONFLICT (agent_id, skill_id) DO UPDATE SET
    enabled=EXCLUDED.enabled, update_time=EXCLUDED.update_time, deleted=EXCLUDED.deleted;

INSERT INTO mate_agent_skill
    (id, agent_id, skill_id, enabled, config_json, create_time, update_time, deleted)
SELECT 1000000963, a.id, s.id, TRUE, NULL, NOW(), NOW(), 0
FROM mate_agent a, mate_skill s
WHERE a.workspace_id = 1 AND a.name = 'SXF-ITDB SQL审批安全官'
  AND s.id = 2091871049277583362 AND s.deleted = 0
FETCH FIRST 1 ROW ONLY
ON CONFLICT (agent_id, skill_id) DO UPDATE SET
    enabled=EXCLUDED.enabled, update_time=EXCLUDED.update_time, deleted=EXCLUDED.deleted;

INSERT INTO mate_agent_skill
    (id, agent_id, skill_id, enabled, config_json, create_time, update_time, deleted)
SELECT 1000000964, a.id, s.id, TRUE, NULL, NOW(), NOW(), 0
FROM mate_agent a, mate_skill s
WHERE a.workspace_id = 1 AND a.name = 'SXF-ITDB SQL审批安全官'
  AND s.id = 2091871106345283585 AND s.deleted = 0
FETCH FIRST 1 ROW ONLY
ON CONFLICT (agent_id, skill_id) DO UPDATE SET
    enabled=EXCLUDED.enabled, update_time=EXCLUDED.update_time, deleted=EXCLUDED.deleted;
