-- V173: register the only tool allowed on the troubleshooting miss path.
-- The tool is read-only and rejects calls without a server-side triage session.

INSERT INTO mate_tool (id, name, display_name, description, tool_type, bean_name, icon, enabled, builtin, create_time, update_time, deleted)
VALUES (1000000028, 'TroubleshootingEvidenceTool', '排障只读取证', '仅在智能排障 miss-path 会话内通过服务端证据路由采集只读证据；无活动会话时拒绝。', 'builtin', 'troubleshootingEvidenceTool', '🔎', TRUE, TRUE, NOW(), NOW(), 0)
ON CONFLICT (id) DO UPDATE SET name=EXCLUDED.name, display_name=EXCLUDED.display_name, description=EXCLUDED.description, tool_type=EXCLUDED.tool_type, bean_name=EXCLUDED.bean_name, icon=EXCLUDED.icon, enabled=EXCLUDED.enabled, builtin=EXCLUDED.builtin, update_time=EXCLUDED.update_time, deleted=EXCLUDED.deleted;
