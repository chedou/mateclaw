---
name: troubleshooting-auth-permission
description: Auth/permission SOP for login failures, missing token permissions, upstream 403, and OAuth callback issues.
version: 1.0.0
type: prompt
category: troubleshooting
author: MateClaw
tags: [troubleshooting, sop, auth, permission]
troubleshooting:
  domain: auth_permission
  scenario: login_token_permission_403
  match:
    severities: [P1, P2, critical, warning]
    labels: [serviceName, env, appId, userId]
    keywords: [login, oauth, token, jwt, permission, 401, 403, unauthorized, forbidden, callback, access_token]
  requiredEvidence: [logs, runbook]
  optionalEvidence: [metrics, release, permission]
  outputSchema: sop-checklist-v1
  owner: platform-sre
  reviewCycleDays: 90
---
# Auth/Permission Troubleshooting SOP

## Applicability

Use this SOP for login failures, token permission mismatch, OAuth callback errors, 401/403, or upstream auth-service denials.

## Evidence Plan

- logs: browser/API error code, auth service logs, upstream denial reason.
- runbook: expected permission/app mapping and token claims checklist.
- optional release/permission: recent app permission, callback URL, secret, or config changes.

## Checklist

1. `failure-path`: capture exact user-visible path, request, status code, and error code.
2. `token-result`: distinguish token issuance success from permission correctness.
3. `claim-permission`: compare token claims/scopes against required app permission.
4. `upstream-denial`: trace upstream 401/403 reason and service-side log handle.
5. `recent-change`: check app permission, callback, secret, config, and release changes.
6. `conclusion`: classify missing permission, invalid token, callback/config issue, upstream denial, or frontend request construction.

## Stop Conditions

Never print raw tokens, cookies, secrets, full authorization headers, or personal identifiers in group output.

## Report Shape

Return strict JSON with `conclusion`, `confidence`, `likelyCauses[]`, `evidenceIds[]`, `nextAction`, and `missingEvidence[]`.
