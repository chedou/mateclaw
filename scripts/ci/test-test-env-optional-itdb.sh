#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PIPELINE="${ROOT_DIR}/Jenkinsfile.test-env"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

if grep -Fq "必须启用 MATECLAW_ITDB_ENABLED=true" "${PIPELINE}"; then
  fail "optional ITDB integration must not block the generic troubleshooting platform"
fi
grep -Fq 'itdb_enabled="$(sed -n' "${PIPELINE}" \
  || fail "pipeline must read the optional ITDB switch explicitly"
grep -Fq 'itdb_integration="DISABLED"' "${PIPELINE}" \
  || fail "pipeline must record the disabled optional capability"
grep -Fq 'if [[ "$itdb_enabled" == "true" ]]' "${PIPELINE}" \
  || fail "credentials and live authentication must only be checked when ITDB is enabled"
grep -Fq 'echo "itdb_integration=$itdb_integration"' "${PIPELINE}" \
  || fail "preflight evidence must state whether ITDB is enabled"

printf 'PASS: optional ITDB is fail-closed without blocking generic troubleshooting\n'
