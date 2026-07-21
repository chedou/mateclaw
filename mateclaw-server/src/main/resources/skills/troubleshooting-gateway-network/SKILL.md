---
name: troubleshooting-gateway-network
description: Gateway/network SOP for 502/504, DNS, TLS, and cross-region link failures.
version: 1.0.0
type: prompt
category: troubleshooting
author: MateClaw
tags: [troubleshooting, sop, gateway, network]
troubleshooting:
  domain: gateway_network
  scenario: gateway_502_504_dns_tls
  match:
    severities: [P1, P2, critical, warning]
    labels: [serviceName, env, gateway, endpoint]
    keywords: [gateway, 502, 504, dns, tls, ssl, timeout, upstream, ingress, nginx, cross-region]
  requiredEvidence: [metrics, logs]
  optionalEvidence: [synthetics, gateway, k8s, release, runbook]
  outputSchema: sop-checklist-v1
  owner: platform-sre
  reviewCycleDays: 90
---
# Gateway/Network Troubleshooting SOP

## Applicability

Use this SOP for gateway 502/504, upstream timeout, DNS/TLS failures, ingress routing errors, or cross-region link symptoms.

## Evidence Plan

- metrics: gateway status code, upstream latency, connection errors, DNS/TLS failure rate.
- logs: gateway access/error logs and upstream service logs.
- optional synthetics/k8s/release: Guance dial-test success rate by region, ingress/service/endpoints changes, and gateway config releases.

## Checklist

1. `edge-scope`: identify domain, route, gateway, upstream service, env, and affected client region.
2. `status-split`: compare gateway status codes with upstream service status.
3. `upstream-health`: check upstream endpoints, pods, and service discovery.
4. `synthetic-region`: if available, compare Guance dial-test failures across regions, ISP nodes, DNS/TLS, status code, and latency.
5. `dns-tls`: inspect DNS resolution, certificate validity, and TLS handshake errors.
6. `config-change`: check ingress/gateway/routing/config releases.
7. `conclusion`: classify gateway config, upstream failure, DNS/TLS, network link, or client-side issue.

## Stop Conditions

Do not expose internal domains with credentials or full headers in group output.

## Report Shape

Return strict JSON with `conclusion`, `confidence`, `likelyCauses[]`, `evidenceIds[]`, `nextAction`, and `missingEvidence[]`.
