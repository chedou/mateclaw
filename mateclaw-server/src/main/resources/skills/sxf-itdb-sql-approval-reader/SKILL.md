---
name: sxf-itdb-sql-approval-reader
description: Read a specific ITDB SQL approval ticket or the current user's pending SQL tickets, including complete SQL, target, backup, execution window, platform review, and logs. Use for read-only ITDB evidence collection; never approve, reject, or execute SQL.
---

# SXF ITDB SQL Approval Reader

Collect a fresh, read-only evidence snapshot from `https://itdb.atrust.sangfor.com`.

## Access

- Prefer the authenticated ITDB API. Reuse the user's browser session when direct API credentials are unavailable.
- For a ticket URL, extract the numeric workflow ID and verify the returned ID matches.
- For pending ownership, query `POST /api/v1/workflow/auditlist/`; do not infer ownership from a detail page alone.
- Load detail with `GET /api/v1/workflow/?workflow_id=<id>&size=2`, SQL Check with `POST /api/v1/workflow/sqlcheck/`, and logs with `POST /api/v1/workflow/log/`.
- Use the page only to recover authentication or fields absent from the API. Label page-only observations.

## Required Evidence

Return the ticket ID, title, requester, current approval node/status, target instance/database, complete SQL, SQL SHA-256, statement count, backup flag, execution window, platform review/check results, affected rows, requirement link, and logs.

Distinguish `PLATFORM_CONFIRMED`, `PAGE_OBSERVED`, and `UNKNOWN`. Redact credentials, tokens, passwords, and unrelated personal data from chat output while retaining the SQL hash.

## Safety Boundary

- Never call `/api/v1/workflow/audit/` or `/api/v1/workflow/execute/`.
- Never claim the ticket is the user's current pending item unless `auditlist` confirms it in the current run.
- On authentication failure, missing SQL, conflicting API/page state, or timeout, stop with an incomplete-evidence result.
