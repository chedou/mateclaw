---
name: troubleshooting-database
description: Database SOP for slow queries, lock waits, pool exhaustion, and replication lag.
version: 1.0.0
type: prompt
category: troubleshooting
author: MateClaw
tags: [troubleshooting, sop, database]
troubleshooting:
  domain: database
  scenario: slow_query_lock_pool
  match:
    severities: [P1, P2, critical, warning]
    labels: [serviceName, env, datasource, dbName]
    keywords: [slow query, lock wait, deadlock, connection pool, pool exhausted, replication lag, mongo, mysql, postgresql, clickhouse]
  requiredEvidence: [metrics, logs]
  optionalEvidence: [release, datasource, runbook]
  outputSchema: sop-checklist-v1
  owner: platform-sre
  reviewCycleDays: 90
---
# Database Troubleshooting SOP

## Applicability

Use this SOP when the alert points at query latency, lock wait, connection pool exhaustion, deadlock, or replication lag.

## Evidence Plan

- metrics: DB latency, QPS, connection count, pool active/idle, lock/replication metrics.
- logs: slow query statements, error signatures, timeout stack traces.
- optional release: recent schema, index, repository, or query change.

## Checklist

1. `scope-impact`: identify affected datasource, database, service, env, and alert window.
2. `pool-health`: check connection pool saturation and timeout rate.
3. `query-pattern`: collect top slow queries and normalize parameters.
4. `lock-replication`: check lock waits, deadlocks, and replication lag.
5. `recent-change`: compare recent code/schema/index/config changes.
6. `conclusion`: decide whether cause is query plan, index, load spike, lock, pool, or downstream DB health.

## Stop Conditions

If SQL samples may contain sensitive values, redact literals before reporting.
If only application timeout exists and no DB metric/log evidence is available, mark evidence insufficient.

## Report Shape

Return strict JSON with `conclusion`, `confidence`, `likelyCauses[]`, `evidenceIds[]`, `nextAction`, and `missingEvidence[]`.
