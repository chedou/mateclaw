-- V223: restore the troubleshooting read-only tool under a collision-free id
-- and seed the dedicated troubleshooting Agent for the default workspace.

INSERT INTO mate_tool
    (id, name, display_name, description, tool_type, bean_name, icon,
     enabled, builtin, create_time, update_time, deleted)
VALUES
    (1000000905, 'TroubleshootingEvidenceTool', '排障只读取证',
     '仅在智能排障 miss-path 会话内通过服务端证据路由采集只读证据；无活动会话时拒绝。',
     'builtin', 'troubleshootingEvidenceTool', '🔎', TRUE, TRUE, NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET
    name=EXCLUDED.name, display_name=EXCLUDED.display_name,
    description=EXCLUDED.description, tool_type=EXCLUDED.tool_type,
    bean_name=EXCLUDED.bean_name, icon=EXCLUDED.icon, enabled=EXCLUDED.enabled,
    builtin=EXCLUDED.builtin, update_time=EXCLUDED.update_time,
    deleted=EXCLUDED.deleted;

INSERT INTO mate_agent
    (id, name, description, agent_type, system_prompt, model_name,
     max_iterations, enabled, icon, tags, workspace_id, create_time,
     update_time, deleted, skills_disabled, tools_disabled, wiki_disabled)
SELECT
    1000000950,
    'troubleshooting-readonly-triage',
    '智能排障机器人：接收告警、只读取证、生成可复核的原因结论。',
    'react',
    '你是智能排障机器人。你只能调用平台允许的只读取证工具，不得修改配置、执行生产变更或编造证据。先说明问题原因，再给出证据、尚未确定项和下一步只读检查。证据不足时必须明确弃权，不得把推测写成已定位根因。',
    'qwen-plus', 4, TRUE, '🩺', 'troubleshooting,readonly,triage',
    1, NOW(), NOW(), 0, TRUE, FALSE, TRUE
WHERE NOT EXISTS (
    SELECT 1 FROM mate_agent
    WHERE workspace_id = 1 AND name = 'troubleshooting-readonly-triage'
);

UPDATE mate_agent
SET description = '智能排障机器人：接收告警、只读取证、生成可复核的原因结论。',
    agent_type = 'react',
    system_prompt = '你是智能排障机器人。你只能调用平台允许的只读取证工具，不得修改配置、执行生产变更或编造证据。先说明问题原因，再给出证据、尚未确定项和下一步只读检查。证据不足时必须明确弃权，不得把推测写成已定位根因。',
    model_name = CASE WHEN model_name IS NULL OR model_name = ''
                      THEN 'qwen-plus' ELSE model_name END,
    max_iterations = 4,
    enabled = TRUE,
    icon = '🩺',
    tags = 'troubleshooting,readonly,triage',
    skills_disabled = TRUE,
    tools_disabled = FALSE,
    wiki_disabled = TRUE,
    deleted = 0,
    update_time = NOW()
WHERE workspace_id = 1 AND name = 'troubleshooting-readonly-triage';

UPDATE mate_agent_tool
SET enabled = FALSE, deleted = 1, update_time = NOW()
WHERE agent_id = (
    SELECT id FROM mate_agent
    WHERE workspace_id = 1 AND name = 'troubleshooting-readonly-triage'
    FETCH FIRST 1 ROW ONLY
)
AND tool_name <> 'TroubleshootingEvidenceTool';

INSERT INTO mate_agent_tool
    (id, agent_id, tool_name, enabled, create_time, update_time, deleted)
SELECT
    1000000951, id, 'TroubleshootingEvidenceTool', TRUE, NOW(), NOW(), 0
FROM mate_agent
WHERE workspace_id = 1 AND name = 'troubleshooting-readonly-triage'
FETCH FIRST 1 ROW ONLY
ON CONFLICT (agent_id, tool_name) DO UPDATE SET
    enabled=EXCLUDED.enabled, update_time=EXCLUDED.update_time,
    deleted=EXCLUDED.deleted;

