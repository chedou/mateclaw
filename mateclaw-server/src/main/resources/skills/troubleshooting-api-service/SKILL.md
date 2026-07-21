---
name: troubleshooting-api-service
description: Service/API alert SOP for HTTP 5xx, latency, timeout, and traffic-drop incidents.
version: 1.0.0
type: prompt
category: troubleshooting
author: MateClaw
tags: [troubleshooting, sop, api, service]
troubleshooting:
  domain: api_service
  scenario: http_5xx_timeout
  match:
    severities: [P1, P2, critical, warning]
    labels: [serviceName, env, cluster, endpoint]
    keywords: [500, 502, 503, 504, timeout, slow, latency, http, api, error_rate]
  requiredEvidence: [metrics, logs, release]
  optionalEvidence: [synthetics, k8s, host, container, gateway, runbook]
  outputSchema: sop-checklist-v1
  owner: platform-sre
  reviewCycleDays: 90
---
# Service/API Troubleshooting SOP

## Applicability

Use this SOP for service/API alerts: HTTP 5xx, timeout, slow endpoint, or request-volume drop.
Do not use it as the primary SOP for database locks, Redis-only errors, or auth-only 403/permission incidents.

## Evidence Plan

- metrics: request rate, error rate, P95/P99 latency, saturation around the alert window.
- logs: top exception signatures, status code distribution, trace/request IDs if present.
- release: recent deployment, config, feature flag, or dependency version changes.
- optional synthetics/k8s/host/container/gateway: Guance dial-test success rate, failed regions, pod restart, readiness/liveness probe, host/container health, upstream gateway 502/504.

## Checklist

1. `scope-impact`: identify affected service, endpoint, env, cluster, start time, and current status.
2. `metric-correlation`: compare traffic, latency, and error rate before/after the alert.
3. `log-signature`: group errors by exception/status/upstream and keep only redacted examples.
4. `release-window`: check recent deploy/config/flag changes in the same service or dependency.
5. `synthetic-impact`: if available, check Guance dial-test failures by endpoint, region, status code, and response time.
6. `dependency-boundary`: decide whether the failure is local service, gateway, downstream, or infra.
7. `conclusion`: rank likely causes and list missing evidence if confidence is low.

## Stop Conditions

Stop with `evidence_insufficient` when metrics, logs, or release evidence is missing.
Never claim a root cause from a single symptom without at least two independent evidence signals.

## Report Shape

Return strict JSON containing `conclusion`, `confidence`, `likelyCauses[]`, `evidenceIds[]`, `nextAction`, and `missingEvidence[]`.
