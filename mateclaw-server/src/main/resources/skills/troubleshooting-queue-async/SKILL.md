---
name: troubleshooting-queue-async
description: Async queue SOP for backlog, consumer failure, duplicate processing, and missing messages.
version: 1.0.0
type: prompt
category: troubleshooting
author: MateClaw
tags: [troubleshooting, sop, queue, async]
troubleshooting:
  domain: queue_async
  scenario: backlog_consume_failure
  match:
    severities: [P1, P2, critical, warning]
    labels: [serviceName, env, topic, consumerGroup]
    keywords: [queue, mq, kafka, rocketmq, backlog, lag, consumer, consume failed, retry, duplicate, dlq]
  requiredEvidence: [metrics, logs]
  optionalEvidence: [release, runbook]
  outputSchema: sop-checklist-v1
  owner: platform-sre
  reviewCycleDays: 90
---
# Queue/Async Troubleshooting SOP

## Applicability

Use this SOP for queue backlog, consumer lag, consume failures, retry storms, dead-letter messages, duplicate processing, or missing messages.

## Evidence Plan

- metrics: topic lag, consumer throughput, retry/DLQ count, produce/consume rate.
- logs: consumer exception signatures, message IDs, redacted payload patterns.
- optional release: consumer deployment, subscription, routing, or schema changes.

## Checklist

1. `scope-impact`: identify topic, consumer group, service, env, and lag window.
2. `lag-trend`: compare produce/consume rate and backlog growth.
3. `consumer-errors`: group consume failures by exception and retry behavior.
4. `message-pattern`: inspect redacted message IDs/types for poison-message or schema drift.
5. `recent-change`: check deploy/config/schema/subscription changes.
6. `conclusion`: classify producer spike, consumer failure, poison message, dependency slowness, or routing change.

## Stop Conditions

Do not include raw payloads in group reports. Use message IDs and redacted schema summaries only.

## Report Shape

Return strict JSON with `conclusion`, `confidence`, `likelyCauses[]`, `evidenceIds[]`, `nextAction`, and `missingEvidence[]`.
