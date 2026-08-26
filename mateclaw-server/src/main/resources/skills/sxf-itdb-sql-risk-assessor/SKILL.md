---
name: sxf-itdb-sql-risk-assessor
description: Assess complete ITDB SQL using platform SQL Check, structural rules, performance and lock risk, sensitive-data checks, rollback readiness, execution window, and business semantics. Use after fresh ticket evidence is available; this skill never submits approval or executes SQL.
---

# SXF ITDB SQL Risk Assessor

Evaluate the complete SQL snapshot identified by ticket ID and SHA-256. Deterministic safety rules take precedence over semantic judgment.

## Assessment Layers

1. Run or consume the fresh ITDB `sqlcheck` result, preserving errors, warnings, critical flags, statement types, and affected rows.
2. Parse statement structure when a parser is available. If structure cannot be established reliably, record an unknown instead of treating a regex match as proof.
3. Identify destructive DDL, missing or ineffective predicates, multi-table writes, cross-database writes, full scans, unbounded joins, hot-table DDL, large transactions, and lock risks.
4. Detect credential-like literals, personal or sensitive data, privilege changes, and irreversible changes. Do not reproduce secrets in output.
5. Compare SQL objects and state transitions with the stated business purpose. Semantic reasoning cannot override a deterministic blocker.
6. Assess backup, rollback method, restoration cost, and execution-window suitability.

## Fail-Closed Rules

- `DROP`, `TRUNCATE`, destructive DDL, production hot-table DDL, or unbounded `UPDATE`/`DELETE` is `HIGH` or `BLOCKED`.
- Platform error/critical, contradictory check state, missing SQL/target, credential-like literals, or an unprovable write scope cannot be low risk.
- Any DDL/DCL, warning, unknown affected scope, missing rollback, or material business unknown requires manual review at minimum.
- A platform label such as “审核通过” is evidence, not an automatic-approval decision.

## Output

Return `risk_level` (`LOW|MEDIUM|HIGH|BLOCKED`), `signals`, `unknowns`, `rollback_assessment`, `recommendation` (`AUTO_APPROVABLE|MANUAL_REVIEW|REJECT`), and evidence items graded `PLATFORM_CONFIRMED|RULE_INFERENCE|UNKNOWN`.

Never call approval, rejection, or SQL execution endpoints.
