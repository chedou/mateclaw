#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CHECKER="${ROOT_DIR}/scripts/ci/check-docker-clone3-compatibility.sh"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf -- "${TMP_DIR}"' EXIT

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

mkdir -p "${TMP_DIR}/bin"
printf '%s\n' \
  'FROM node:22-alpine AS builder' \
  'FROM mcr.microsoft.com/playwright:v1.62.0-noble' \
  > "${TMP_DIR}/Dockerfile"
printf '%s\n' \
  '#!/usr/bin/env bash' \
  'case "${1:-}" in' \
  '  pull) exit "${FAKE_DOCKER_PULL_RESULT:-0}" ;;' \
  '  inspect) printf "sha256:legacy-probe-image\n"; exit 0 ;;' \
  '  run) exit "${FAKE_DOCKER_RUN_RESULT:-0}" ;;' \
  '  *) exit 99 ;;' \
  'esac' \
  > "${TMP_DIR}/bin/docker"
chmod +x "${TMP_DIR}/bin/docker"

expect_pass() {
  local version="$1"
  local security_options="${2:-name=seccomp,profile=default}"
  local docker_run_result="${3:-0}"
  PATH="${TMP_DIR}/bin:${PATH}" FAKE_DOCKER_RUN_RESULT="${docker_run_result}" \
    "${CHECKER}" "${version}" "${security_options}" "${TMP_DIR}/Dockerfile" >/dev/null \
    || fail "expected Docker ${version} to pass"
}

expect_fail() {
  local version="$1"
  local security_options="${2:-name=seccomp,profile=default}"
  local docker_run_result="${3:-0}"
  if PATH="${TMP_DIR}/bin:${PATH}" FAKE_DOCKER_RUN_RESULT="${docker_run_result}" \
    "${CHECKER}" "${version}" "${security_options}" "${TMP_DIR}/Dockerfile" >/dev/null 2>&1; then
    fail "expected Docker ${version} to fail"
  fi
}

expect_pass '18.06.0-ce'
expect_fail '18.06.0-ce' 'name=apparmor'
expect_fail '18.06.0-ce' 'name=seccomp,profile=unconfined'
expect_fail '18.06.0-ce' 'name=seccomp,profile=default' '1'
expect_fail '17.12.1-ce'
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

printf 'FROM alpine:3.20\n' > "${TMP_DIR}/wrong-runtime.Dockerfile"
if PATH="${TMP_DIR}/bin:${PATH}" \
  "${CHECKER}" '18.06.0-ce' 'name=seccomp,profile=default' \
  "${TMP_DIR}/wrong-runtime.Dockerfile" >/dev/null 2>&1; then
  fail "legacy probe must reject a runtime Dockerfile without the reviewed Playwright base"
fi

if grep -Fq -- '--security-opt seccomp=unconfined' "${ROOT_DIR}/Jenkinsfile.test-env"; then
  fail "test-environment pipeline must not disable seccomp"
fi
grep -Fq -- 'mateclaw-server/Dockerfile' "${ROOT_DIR}/Jenkinsfile.test-env" \
  || fail "pipeline must pass the production Dockerfile to the compatibility checker"
grep -Fq -- 'runtime-compatibility.txt' "${ROOT_DIR}/Jenkinsfile.test-env" \
  || fail "pipeline must archive the checker-owned compatibility evidence"
if grep -Fq -- "LEGACY_RUNTIME_PROBE_IMAGE" "${ROOT_DIR}/Jenkinsfile.test-env"; then
  fail "pipeline must not duplicate the runtime image pinned by the Dockerfile"
fi
if grep -Fq -- 'docker_clone3_seccomp=SUPPORTED' "${ROOT_DIR}/Jenkinsfile.test-env"; then
  fail "legacy probe success must not be mislabeled as native clone3 support"
fi

printf 'PASS: Docker compatibility gate owns the exact-image seccomp probe for Docker 18.06\n'
