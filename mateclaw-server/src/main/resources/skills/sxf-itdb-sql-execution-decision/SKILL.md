---
name: sxf-itdb-sql-execution-decision
description: Convert fresh ITDB ticket evidence and SQL risk signals into AUTO_APPROVABLE, MANUAL_REVIEW, or REJECT with deterministic veto rules. Use to decide whether approval may be submitted; it never means SQL execution is authorized.
---

# SXF ITDB SQL Execution Decision

Produce an auditable approval recommendation while keeping approval and SQL execution separate.

## Deterministic Vetoes

Return `REJECT` when the SQL has an unbounded or destructive write, platform error/critical, credential-like literals, unauthorized or unexplained cross-database writes, or another confirmed blocker.

Return `MANUAL_REVIEW` when the ticket is not freshly confirmed in the current user's pending list, its target/SQL/hash is incomplete or changed, any DDL/DCL is present, a warning or important unknown remains, affected rows are unknown or above policy, rollback is inadequate, the window is unsafe, or business intent cannot be matched.

Return `AUTO_APPROVABLE` only when all are true:

- Current pending ownership and node are freshly confirmed.
- Target and SQL SHA-256 are stable.
- The ticket contains only supported bounded DML; no DDL/DCL or batch delete.
- A primary/unique-key predicate proves a small scope, default at most 10 affected rows.
- Platform result is consistent with zero error, critical, and warning.
- Backup or a proven rollback exists, the time window is valid, and no sensitive literal or key business unknown remains.

## Required Result

Return `recommendation`, `can_submit_approval`, `can_execute_sql=false`, `blocking_reasons`, `residual_risks`, `unknowns`, `evidence`, `confirmation_required=true`, and the ticket ID/SQL hash used.

This Skill is read-only. `AUTO_APPROVABLE` permits displaying a per-ticket confirmation; it does not itself authorize the approval API or `/execute/`.
