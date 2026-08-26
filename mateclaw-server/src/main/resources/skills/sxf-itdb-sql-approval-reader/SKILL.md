---
name: sxf-itdb-sql-approval-reader
description: Read a specific ITDB SQL approval ticket or the current user's pending SQL tickets, including complete SQL, target, backup, execution window, platform review, and logs. Use for read-only ITDB evidence collection; never approve, reject, or execute SQL.
allowed-tools:
  - itdb_pending_sql_requests
  - itdb_review_sql_request
---

# SXF ITDB SQL Approval Reader

Collect a fresh, read-only evidence snapshot through MateClaw's native `ItDbWorkflowTool` callbacks. The callbacks use authenticated ITDB HTTP APIs; this Skill never drives a browser or reads a rendered ITDB page.

## Access

- Call `itdb_pending_sql_requests` to read the configured reviewer's live pending queue.
- For a ticket URL, extract the numeric workflow ID and verify the returned ID matches.
- For pending ownership, trust only the current `itdb_pending_sql_requests` result; do not infer ownership from a ticket URL.
- Call `itdb_review_sql_request(ticketId)` to reload detail, complete SQL, SQL Check, workflow logs, SQL SHA-256, and the deterministic read-only decision.
- If either native callback is unavailable or authentication fails, stop with incomplete evidence. Do not fall back to browser automation.

## Required Evidence

Return the ticket ID, title, requester, current approval node/status, target instance/database, complete SQL, SQL SHA-256, statement count, backup flag, execution window, platform review/check results, affected rows, requirement link, and logs.

Distinguish `PLATFORM_CONFIRMED`, `RULE_INFERENCE`, and `UNKNOWN`. Redact credentials, tokens, passwords, and unrelated personal data from chat output while retaining the SQL hash.

## Safety Boundary

- Never call `itdb_approve_sql_request` or any SQL execution endpoint.
- Never claim the ticket is the user's current pending item unless `itdb_pending_sql_requests` confirms it in the current run.
- On authentication failure, missing SQL, conflicting API state, or timeout, stop with an incomplete-evidence result.
