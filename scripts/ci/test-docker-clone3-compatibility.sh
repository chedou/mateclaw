#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CHECKER="${ROOT_DIR}/scripts/ci/check-docker-clone3-compatibility.sh"
SECCOMP_PROFILE="${ROOT_DIR}/deploy/seccomp/docker18-clone3.json"
SECCOMP_SHA256="959c7b5f83f4fa6f0bec17dab25434fafa399b11e84661a30c725bece3d5473d"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf -- "${TMP_DIR}"' EXIT

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

mkdir -p "${TMP_DIR}/bin"
printf '%s\n' \
  '#!/usr/bin/env bash' \
  'if [[ "${1:-}" == "-q" && "${2:-}" == "--qf" && "${4:-}" == "libseccomp" ]]; then' \
  '  printf "%s" "${FAKE_LIBSECCOMP_VERSION:-2.5.0}"' \
  '  exit "${FAKE_RPM_RESULT:-0}"' \
  'fi' \
  'exit 99' \
  > "${TMP_DIR}/bin/rpm"
chmod +x "${TMP_DIR}/bin/rpm"
printf '%s\n' \
  'FROM node:22-alpine AS builder' \
  'FROM mcr.microsoft.com/playwright:v1.62.0-noble' \
  > "${TMP_DIR}/Dockerfile"
printf '%s\n' \
  '#!/usr/bin/env bash' \
  'case "${1:-}" in' \
  '  pull)' \
  '    [[ "${FAKE_DOCKER_PULL_RESULT:-0}" == "0" ]] || exit "${FAKE_DOCKER_PULL_RESULT}"' \
  '    : > "${FAKE_DOCKER_STATE}"' \
  '    exit 0' \
  '    ;;' \
  '  inspect)' \
  '    if [[ "${FAKE_DOCKER_IMAGE_PRESENT:-1}" == "1" || -f "${FAKE_DOCKER_STATE}" ]]; then' \
  '      printf "sha256:legacy-probe-image\n"' \
  '      exit 0' \
  '    fi' \
  '    exit 1' \
  '    ;;' \
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
    "${CHECKER}" "${version}" "${security_options}" "${TMP_DIR}/Dockerfile" "${SECCOMP_PROFILE}" >/dev/null \
    || fail "expected Docker ${version} to pass"
}

expect_fail() {
  local version="$1"
  local security_options="${2:-name=seccomp,profile=default}"
  local docker_run_result="${3:-0}"
  if PATH="${TMP_DIR}/bin:${PATH}" FAKE_DOCKER_RUN_RESULT="${docker_run_result}" \
    "${CHECKER}" "${version}" "${security_options}" "${TMP_DIR}/Dockerfile" "${SECCOMP_PROFILE}" >/dev/null 2>&1; then
    fail "expected Docker ${version} to fail"
  fi
}

expect_pass '18.06.0-ce'
if PATH="${TMP_DIR}/bin:${PATH}" FAKE_LIBSECCOMP_VERSION=2.3.1 \
  "${CHECKER}" '18.06.0-ce' 'name=seccomp,profile=default' \
  "${TMP_DIR}/Dockerfile" "${SECCOMP_PROFILE}" >"${TMP_DIR}/old-libseccomp.out" 2>&1; then
  fail "Docker 18 must reject libseccomp versions that cannot resolve clone3"
fi
grep -Fq 'libseccomp 2.3.1 不认识 clone3' "${TMP_DIR}/old-libseccomp.out" \
  || fail "old libseccomp rejection must explain the real clone3 blocker"
if PATH="${TMP_DIR}/bin:${PATH}" FAKE_RPM_RESULT=1 \
  "${CHECKER}" '18.06.0-ce' 'name=seccomp,profile=default' \
  "${TMP_DIR}/Dockerfile" "${SECCOMP_PROFILE}" >/dev/null 2>&1; then
  fail "Docker 18 must reject an unverifiable libseccomp installation"
fi
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

rm -f "${TMP_DIR}/docker-state"
pulled_output="$(
  PATH="${TMP_DIR}/bin:${PATH}" \
    FAKE_DOCKER_IMAGE_PRESENT=0 \
    FAKE_DOCKER_STATE="${TMP_DIR}/docker-state" \
    "${CHECKER}" '18.06.0-ce' 'name=seccomp,profile=default' "${TMP_DIR}/Dockerfile" "${SECCOMP_PROFILE}"
)"
grep -Fq 'legacy_runtime_probe_image_source=PULLED' <<<"${pulled_output}" \
  || fail "legacy probe must report when it pulled a missing runtime image"

rm -f "${TMP_DIR}/docker-state"
if PATH="${TMP_DIR}/bin:${PATH}" \
  FAKE_DOCKER_IMAGE_PRESENT=0 \
  FAKE_DOCKER_PULL_RESULT=1 \
  FAKE_DOCKER_STATE="${TMP_DIR}/docker-state" \
  "${CHECKER}" '18.06.0-ce' 'name=seccomp,profile=default' "${TMP_DIR}/Dockerfile" "${SECCOMP_PROFILE}" \
  >/dev/null 2>&1; then
  fail "legacy probe must fail when the runtime image is absent and cannot be pulled"
fi

printf 'FROM alpine:3.20\n' > "${TMP_DIR}/wrong-runtime.Dockerfile"
if PATH="${TMP_DIR}/bin:${PATH}" \
  "${CHECKER}" '18.06.0-ce' 'name=seccomp,profile=default' \
  "${TMP_DIR}/wrong-runtime.Dockerfile" "${SECCOMP_PROFILE}" >/dev/null 2>&1; then
  fail "legacy probe must reject a runtime Dockerfile without the reviewed Playwright base"
fi

if grep -Fq -- '--security-opt seccomp=unconfined' "${ROOT_DIR}/Jenkinsfile.test-env"; then
  fail "test-environment pipeline must not disable seccomp"
fi
[[ "$(sha256sum "${SECCOMP_PROFILE}" | awk '{print $1}')" == "${SECCOMP_SHA256}" ]] \
  || fail "reviewed Docker 18 seccomp profile hash changed"
jq -e '.defaultAction == "SCMP_ACT_ERRNO"' "${SECCOMP_PROFILE}" >/dev/null \
  || fail "Docker 18 seccomp profile must remain default-deny"
jq -e '[.syscalls[].names[]? | select(. == "clone3")] | length == 1' "${SECCOMP_PROFILE}" >/dev/null \
  || fail "Docker 18 seccomp profile must allow clone3 exactly once"
grep -Fq -- 'deploy/seccomp/docker18-clone3.json' "${ROOT_DIR}/Jenkinsfile.test-env" \
  || fail "pipeline must use the reviewed Docker 18 seccomp profile"
grep -Fq -- 'mateclaw-server/Dockerfile' "${ROOT_DIR}/Jenkinsfile.test-env" \
  || fail "pipeline must pass the production Dockerfile to the compatibility checker"
grep -Fq -- 'runtime-compatibility.txt' "${ROOT_DIR}/Jenkinsfile.test-env" \
  || fail "pipeline must archive the checker-owned compatibility evidence"
grep -Fq -- 'legacy_libseccomp_version=' "${ROOT_DIR}/Jenkinsfile.test-env" \
  || fail "pipeline must record the verified Docker 18 libseccomp version"
if grep -Fq -- "LEGACY_RUNTIME_PROBE_IMAGE" "${ROOT_DIR}/Jenkinsfile.test-env"; then
  fail "pipeline must not duplicate the runtime image pinned by the Dockerfile"
fi
if grep -Fq -- 'docker_clone3_seccomp=SUPPORTED' "${ROOT_DIR}/Jenkinsfile.test-env"; then
  fail "legacy probe success must not be mislabeled as native clone3 support"
fi

printf 'PASS: Docker compatibility gate owns the exact-image seccomp probe for Docker 18.06\n'
