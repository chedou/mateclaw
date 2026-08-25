-- V224: Register the controlled ITDB SQL review/approval bean.
-- The bean exposes three runtime functions: pending list, deterministic review,
-- and single-ticket approval. The approval function is separately guarded by
-- ItDbApprovalGuardian and never calls ITDB's execute endpoint.

INSERT INTO mate_tool (
    id, name, display_name, description, tool_type, bean_name, icon,
    enabled, builtin, disclosure_tier, create_time, update_time, deleted
)
VALUES (
    1000000906,
    'ItDbWorkflowTool',
    'SXF-ITDB SQL 审批',
    'Read the live ITDB pending queue, review complete SQL with deterministic risk controls, and submit one low-risk approval through MateClaw human confirmation. Approval advances workflow only and never executes SQL.',
    'builtin',
    'itDbWorkflowTool',
    '🛡️',
    TRUE,
    TRUE,
    'core',
    NOW(),
    NOW(),
    0
)
ON CONFLICT (id) DO UPDATE SET
    name = EXCLUDED.name,
    display_name = EXCLUDED.display_name,
    description = EXCLUDED.description,
    tool_type = EXCLUDED.tool_type,
    bean_name = EXCLUDED.bean_name,
    icon = EXCLUDED.icon,
    enabled = EXCLUDED.enabled,
    builtin = EXCLUDED.builtin,
    disclosure_tier = EXCLUDED.disclosure_tier,
    update_time = EXCLUDED.update_time,
    deleted = EXCLUDED.deleted;
