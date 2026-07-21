---
name: troubleshooting-frontend-browser
description: Frontend/browser SOP for page errors, CORS, request assembly mistakes, and client-side regressions.
version: 1.0.0
type: prompt
category: troubleshooting
author: MateClaw
tags: [troubleshooting, sop, frontend, browser]
troubleshooting:
  domain: frontend_browser
  scenario: page_error_cors_manual_api
  match:
    severities: [P1, P2, critical, warning]
    labels: [serviceName, env, page, endpoint]
    keywords: [frontend, browser, cors, console, network, api, manual request, page flow, javascript, js error]
  requiredEvidence: [logs, runbook]
  optionalEvidence: [synthetics, metrics, release, browser]
  outputSchema: sop-checklist-v1
  owner: platform-sre
  reviewCycleDays: 90
---
# Frontend/Browser Troubleshooting SOP

## Applicability

Use this SOP for browser console errors, CORS failures, broken page flows, frontend request defaults, or manually assembled API calls.

## Evidence Plan

- logs: browser console/network error, BFF/API error, frontend release version.
- runbook: expected page flow, request schema, default field behavior.
- optional synthetics/browser: Guance dial-test status for page/API entrypoint, screenshot, HAR, route state, feature flag, cache status.

## Checklist

1. `entry-path`: ask whether the request came from normal page flow or was manually assembled for an API call.
2. `browser-symptom`: capture console error, network status, request URL, and redacted payload shape.
3. `schema-defaults`: compare actual request with expected page-flow defaults and required fields.
4. `server-correlation`: correlate frontend request ID/time with BFF/API logs.
5. `synthetic-entrypoint`: if available, check Guance dial-test status for the page/API entrypoint and compare with user-side browser evidence.
6. `recent-release`: check frontend/BFF release and feature flag changes.
7. `conclusion`: classify frontend regression, request assembly issue, backend validation, CORS/gateway, or cache/session state.

## Stop Conditions

Do not include cookies, authorization headers, or full user data in group output.

## Report Shape

Return strict JSON with `conclusion`, `confidence`, `likelyCauses[]`, `evidenceIds[]`, `nextAction`, and `missingEvidence[]`.
