---
name: sxf-itdb-sql-approval-submit
description: Submit one ITDB SQL workflow approval through the official approval API after fresh revalidation and explicit per-ticket confirmation, then verify the new workflow transition. Never execute SQL, bulk approve, reject implicitly, or retry an uncertain write automatically.
---

# SXF ITDB SQL Approval Submit

Use the official ITDB approval API only for one ticket whose latest decision is `AUTO_APPROVABLE`.

## Mandatory Sequence

1. Requery `POST /api/v1/workflow/auditlist/` and confirm the ticket is still pending for the current authenticated user/node.
2. Reload `GET /api/v1/workflow/?workflow_id=<id>&size=2`; compare SQL SHA-256, target, database, backup, and execution window with the reviewed snapshot.
3. Rerun `POST /api/v1/workflow/sqlcheck/`. Any state or evidence change stops submission and returns to manual review.
4. Show exactly one ticket-specific confirmation line: `工单 <ID>：低风险，预计影响 <范围/行数>，建议通过；主要剩余风险是 <风险或无明显风险>。现在提交“审核通过”吗？`
5. Accept only an explicit confirmation referring to the surfaced ticket. Standing permission, silence, or an earlier ticket's confirmation is not sufficient.
6. Call `POST /api/v1/workflow/audit/` once with the verified ticket/type, a traceable remark, and `audit_type=pass`.
7. Query logs and refresh the pending list. Report success only when a new matching approval/pass log is observed and the ticket leaves the current pending node.

## Safety Boundary

- Never call `/api/v1/workflow/execute/`; approval advances workflow and does not mean SQL executed.
- Never bulk approve or submit `cancel`/reject without an explicit separate instruction.
- If the approval request times out or post-verification is uncertain, do not retry automatically. Return `verification_required` and inspect logs/pending state.
- Do not expose session cookies, JWTs, credentials, or sensitive SQL literals.
