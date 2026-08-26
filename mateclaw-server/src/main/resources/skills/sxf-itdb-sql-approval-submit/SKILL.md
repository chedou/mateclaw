---
name: sxf-itdb-sql-approval-submit
description: Submit one ITDB SQL workflow approval through the official approval API after fresh revalidation and explicit per-ticket confirmation, then verify the new workflow transition. Never execute SQL, bulk approve, reject implicitly, or retry an uncertain write automatically.
allowed-tools:
  - itdb_pending_sql_requests
  - itdb_review_sql_request
  - itdb_approve_sql_request
---

# SXF ITDB SQL Approval Submit

Use MateClaw's native `ItDbWorkflowTool` callbacks only. They call the authenticated ITDB HTTP API directly; never submit approval through browser automation. Process exactly one ticket whose latest decision is `AUTO_APPROVABLE`.

## Mandatory Sequence

1. Call `itdb_pending_sql_requests` and confirm the ticket is still pending for the configured authenticated reviewer/node.
2. Call `itdb_review_sql_request(ticketId)`; compare SQL SHA-256, target, database, backup, execution window, and decision with the reviewed snapshot.
3. Any state, evidence, hash, or decision change stops submission and returns to manual review.
4. Show exactly one ticket-specific confirmation line: `工单 <ID>：低风险，预计影响 <范围/行数>，建议通过；主要剩余风险是 <风险或无明显风险>。现在提交“审核通过”吗？`
5. Accept only an explicit confirmation referring to the surfaced ticket. Standing permission, silence, or an earlier ticket's confirmation is not sufficient.
6. Call `itdb_approve_sql_request(ticketId, expectedSqlSha256, approvalRemark)` exactly once. MateClaw's persisted human-approval guard must authorize this tool call.
7. Trust success only when the callback reports both a new matching approval/pass log and that the ticket left the current pending node.

## Safety Boundary

- Never call any SQL execution endpoint; `itdb_approve_sql_request` advances one approval node and does not mean SQL executed.
- Never bulk approve or submit `cancel`/reject without an explicit separate instruction.
- If the approval request times out or post-verification is uncertain, do not retry automatically. Return `verification_required` and inspect logs/pending state.
- Do not expose session cookies, JWTs, credentials, or sensitive SQL literals.
