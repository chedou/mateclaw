#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORKFLOW="${ROOT_DIR}/.github/workflows/troubleshooting-smoke.yml"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

assert_contains() {
  local needle="$1"
  grep -Fq -- "${needle}" "${WORKFLOW}" \
    || fail "workflow must contain: ${needle}"
}

assert_order() {
  local earlier="$1" later="$2"
  local earlier_line later_line
  earlier_line="$(grep -nF -- "${earlier}" "${WORKFLOW}" | head -n 1 | cut -d: -f1)"
  later_line="$(grep -nF -- "${later}" "${WORKFLOW}" | head -n 1 | cut -d: -f1)"
  [[ -n "${earlier_line}" && -n "${later_line}" ]] \
    || fail "cannot compare missing workflow steps: ${earlier} -> ${later}"
  (( earlier_line < later_line )) \
    || fail "workflow must run '${earlier}' before '${later}'"
}

[[ -f "${WORKFLOW}" ]] || fail "missing workflow: .github/workflows/troubleshooting-smoke.yml"

assert_contains "pull_request:"
assert_contains "push:"
assert_contains "workflow_dispatch:"
assert_contains "actions/checkout@v6"
assert_contains "actions/setup-java@v5"
assert_contains "java-version: '21'"
assert_contains "-pl mateclaw-plugin-api"
assert_contains "spring-boot:run"
assert_contains "dev,troubleshooting-demo"
assert_contains "{1..60}"
assert_contains "sleep 2"
assert_contains "/sops/csdp/903001"
assert_contains '[[ "${playbook_status}" == "approved" ]]'
assert_contains "./scripts/troubleshooting-smoke.sh"
assert_contains "MATECLAW_USERNAME: admin"
assert_contains "MATECLAW_PASSWORD: admin123"
assert_contains "300"
assert_contains 'GITHUB_STEP_SUMMARY'
assert_contains "if: always()"
assert_contains "actions/upload-artifact@v7"
assert_contains "kill"
assert_order "-pl mateclaw-plugin-api" "spring-boot:run"

if grep -Eqi 'guance|fixtureMode[[:space:]]*:[[:space:]]*false' "${WORKFLOW}"; then
  fail "workflow must stay fixture-only and must not configure Guance"
fi

printf 'PASS: troubleshooting smoke workflow contract\n'
