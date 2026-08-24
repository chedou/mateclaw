#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CHECKER="${ROOT_DIR}/scripts/ci/check-docker-clone3-compatibility.sh"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

expect_pass() {
  local version="$1"
  local security_options="${2:-name=seccomp,profile=default}"
  "${CHECKER}" "${version}" "${security_options}" >/dev/null \
    || fail "expected Docker ${version} to pass"
}

expect_fail() {
  local version="$1"
  local security_options="${2:-name=seccomp,profile=default}"
  if "${CHECKER}" "${version}" "${security_options}" >/dev/null 2>&1; then
    fail "expected Docker ${version} to fail"
  fi
}

expect_fail '18.06.0-ce'
expect_fail '20.10.9'
expect_fail 'not-a-version'
expect_pass '20.10.10'
expect_pass '20.10.10-ce'
expect_pass '20.10.24+dfsg1' $'name=apparmor\nname=seccomp,profile=builtin'
expect_pass '24.0.7'
expect_pass '28.3.3'
expect_fail '20.10.24+dfsg1' 'name=apparmor'
expect_fail '20.10.24+dfsg1' 'name=seccomp,profile=unconfined'
expect_fail '20.10.24+dfsg1' 'name=seccomp-disabled'
expect_fail '20.10.9+dfsg1'

if grep -Fq -- '--security-opt seccomp=unconfined' "${ROOT_DIR}/Jenkinsfile.test-env"; then
  fail "test-environment pipeline must not disable seccomp"
fi

printf 'PASS: Docker clone3 compatibility gate rejects legacy engines\n'
