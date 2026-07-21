---
name: troubleshooting-release-k8s
description: Release/K8s SOP for post-deploy incidents, pod restarts, probe failures, and config drift.
version: 1.0.0
type: prompt
category: troubleshooting
author: MateClaw
tags: [troubleshooting, sop, release, k8s]
troubleshooting:
  domain: release_k8s
  scenario: post_deploy_pod_restart
  match:
    severities: [P1, P2, critical, warning]
    labels: [serviceName, env, cluster, namespace, pod]
    keywords: [deploy, release, rollout, pod, restart, crashloop, readiness, liveness, oom, configmap, image]
  requiredEvidence: [release, k8s]
  optionalEvidence: [metrics, logs, host, container, runbook]
  outputSchema: sop-checklist-v1
  owner: platform-sre
  reviewCycleDays: 90
---
# Release/K8s Troubleshooting SOP

## Applicability

Use this SOP when symptoms appear after release/config changes or K8s reports pod restart, probe failure, CrashLoopBackOff, OOM, or image issues.

## Evidence Plan

- release: recent deploy, image tag, configmap/secret/feature flag changes.
- k8s: pod phase, restart count, last state, events, probe failures, resource limits.
- optional metrics/logs/host/container: service saturation, application exception signatures, Guance host health, and container runtime snapshot.

## Checklist

1. `release-window`: find deployments/config changes around the alert start time.
2. `pod-health`: check restart count, last termination reason, OOM, and pod events.
3. `probe-status`: verify readiness/liveness failures and endpoint availability.
4. `config-drift`: compare image tag, env, configmap, secret, and resource limits.
5. `blast-radius`: determine whether only new pods, one namespace, or all replicas are affected.
6. `conclusion`: classify bad release, config drift, resource pressure, probe regression, or unrelated incident.

## Stop Conditions

Never recommend rollback automatically; report rollback as a manual option with evidence and risk.

## Report Shape

Return strict JSON with `conclusion`, `confidence`, `likelyCauses[]`, `evidenceIds[]`, `nextAction`, and `missingEvidence[]`.
