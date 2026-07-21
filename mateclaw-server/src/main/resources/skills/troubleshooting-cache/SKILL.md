---
name: troubleshooting-cache
description: Cache SOP for Redis timeout, low hit rate, eviction, penetration, and stampede incidents.
version: 1.0.0
type: prompt
category: troubleshooting
author: MateClaw
tags: [troubleshooting, sop, cache, redis]
troubleshooting:
  domain: cache
  scenario: redis_timeout_hit_rate
  match:
    severities: [P1, P2, critical, warning]
    labels: [serviceName, env, cluster, redis]
    keywords: [redis, cache, timeout, hit rate, miss, eviction, memory, hot key, penetration, avalanche]
  requiredEvidence: [metrics, logs]
  optionalEvidence: [release, runbook]
  outputSchema: sop-checklist-v1
  owner: platform-sre
  reviewCycleDays: 90
---
# Cache Troubleshooting SOP

## Applicability

Use this SOP for Redis/cache timeout, hit-rate drop, memory eviction, hot key, penetration, or cache avalanche symptoms.

## Evidence Plan

- metrics: Redis latency, ops, memory, evicted keys, hit/miss rate, slowlog count.
- logs: cache timeout stack traces, key pattern summaries, fallback DB pressure.
- optional release: TTL/key-prefix/cache policy changes.

## Checklist

1. `scope-impact`: identify cache cluster, service, env, and affected key/domain.
2. `latency-health`: check Redis latency, saturation, memory, and evictions.
3. `hit-rate`: compare hit/miss rate before and after alert.
4. `key-pattern`: identify hot key, large key, or missing-key storm patterns.
5. `fallback-pressure`: check whether DB/API fallback was amplified.
6. `conclusion`: classify timeout, hot key, memory pressure, policy change, or upstream/downstream amplification.

## Stop Conditions

Do not print raw cache keys if they contain user or tenant identifiers; summarize key patterns.

## Report Shape

Return strict JSON with `conclusion`, `confidence`, `likelyCauses[]`, `evidenceIds[]`, `nextAction`, and `missingEvidence[]`.
