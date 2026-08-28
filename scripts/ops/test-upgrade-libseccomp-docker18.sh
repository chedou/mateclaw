#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
UPGRADER="${ROOT_DIR}/scripts/ops/upgrade-libseccomp-docker18.sh"
TMP_DIR="$(mktemp -d)"
trap 'find "${TMP_DIR}" -mindepth 1 -delete; rmdir "${TMP_DIR}"' EXIT

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

grep -Fq 'syscalls.perf.c' "${UPGRADER}" \
  || fail "official release must use its pre-generated syscall table"
grep -Fq 'GPERF="${gperf_guard}"' "${UPGRADER}" \
  || fail "build must fail if it unexpectedly tries to regenerate the syscall table"
grep -Fq 'timeout --signal=TERM --kill-after=2s' "${UPGRADER}" \
  || fail "production Docker maintenance calls must be bounded"
grep -Fq 'probe_container="mateclaw-libseccomp-probe-$$"' "${UPGRADER}" \
  || fail "thread probe must use an exact unique container name"
report_commit_line="$(grep -nF 'mv -f -- "${report_tmp}" "${STATE_DIR}/activation-report.txt"' "${UPGRADER}" | cut -d: -f1)"
rollback_disable_line="$(grep -nF 'switched=0' "${UPGRADER}" | tail -1 | cut -d: -f1)"
[[ -n "${report_commit_line}" && -n "${rollback_disable_line}" ]] \
  || fail "activation report or rollback disable step is missing"
(( report_commit_line < rollback_disable_line )) \
  || fail "rollback must remain armed until the activation report is committed"
if grep -Eq '(^|[[:space:]])(yum|dnf)[[:space:]]+.*install' "${UPGRADER}"; then
  fail "shared host maintenance must not install build packages"
fi
if grep -Fq 'mcr.microsoft.com/playwright' "${UPGRADER}"; then
  fail "maintenance must not carry a public Playwright image default"
fi

mkdir -p "${TMP_DIR}/host/lib64" "${TMP_DIR}/prefix/lib64" "${TMP_DIR}/bin"
printf 'old-library\n' > "${TMP_DIR}/host/lib64/libseccomp.so.2.3.1"
ln -s libseccomp.so.2.3.1 "${TMP_DIR}/host/lib64/libseccomp.so.2"
printf 'new-library\n' > "${TMP_DIR}/prefix/lib64/libseccomp.so.2.5.6"
printf '{}\n' > "${TMP_DIR}/profile.json"

printf '%s\n' \
  '#!/usr/bin/env bash' \
  'target="$(readlink "${MATECLAW_TEST_LIBDIR}/libseccomp.so.2")"' \
  'case "${target}" in' \
  '  *2.5.6*) printf "2.5.6\n" ;;' \
  '  *) printf "2.3.1\n" ;;' \
  'esac' \
  > "${TMP_DIR}/bin/detect-libseccomp"
chmod +x "${TMP_DIR}/bin/detect-libseccomp"

printf '%s\n' \
  '#!/usr/bin/env bash' \
  'printf "%s\n" "$*" >> "${FAKE_DOCKER_CALLS_FILE:?}"' \
  'case "${1:-}" in' \
  '  version) printf "18.06.0-ce\n" ;;' \
  '  info) exit 0 ;;' \
  '  ps) printf "20\n" ;;' \
  '  run) exit "${FAKE_DOCKER_PROBE_RESULT:-0}" ;;' \
  '  *) exit 99 ;;' \
  'esac' \
  > "${TMP_DIR}/bin/docker"
chmod +x "${TMP_DIR}/bin/docker"

common_env=(
  PATH="${TMP_DIR}/bin:${PATH}"
  MATECLAW_LIBSECCOMP_TEST_MODE=1
  MATECLAW_LIBSECCOMP_LIBDIR="${TMP_DIR}/host/lib64"
  MATECLAW_LIBSECCOMP_PREFIX="${TMP_DIR}/prefix"
  MATECLAW_LIBSECCOMP_STATE_DIR="${TMP_DIR}/state"
  MATECLAW_LIBSECCOMP_DETECTOR="${TMP_DIR}/bin/detect-libseccomp"
  MATECLAW_TEST_LIBDIR="${TMP_DIR}/host/lib64"
  FAKE_DOCKER_CALLS_FILE="${TMP_DIR}/docker-calls.log"
)
runtime_image="itharbor.sangfor.com/ai-uat/mateclaw-playwright:v1.62.0-noble@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

assert_rejected_before_docker() {
  expected_message="$1"
  shift
  : > "${TMP_DIR}/docker-calls.log"
  if output="$(env -u MATECLAW_RUNTIME_BASE_IMAGE "${common_env[@]}" "$@" \
    "${UPGRADER}" --activate "${TMP_DIR}/profile.json" 2>&1)"; then
    fail "invalid runtime image input must fail closed"
  fi
  [[ "${output}" == *"${expected_message}"* ]] \
    || fail "runtime image rejection should explain the required internal pinned reference"
  [[ ! -s "${TMP_DIR}/docker-calls.log" ]] \
    || fail "runtime image validation must run before every Docker operation"
}

assert_rejected_before_docker "MATECLAW_RUNTIME_BASE_IMAGE" \
  env -u MATECLAW_RUNTIME_BASE_IMAGE
assert_rejected_before_docker "itharbor.sangfor.com" \
  env MATECLAW_RUNTIME_BASE_IMAGE="mcr.microsoft.com/playwright:v1.62.0-noble@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
assert_rejected_before_docker "sha256" \
  env MATECLAW_RUNTIME_BASE_IMAGE="itharbor.sangfor.com/ai-uat/mateclaw-playwright:v1.62.0-noble"
assert_rejected_before_docker "v1.62.0-noble" \
  env MATECLAW_RUNTIME_BASE_IMAGE="itharbor.sangfor.com/ai-uat/mateclaw-playwright:v1.61.0-noble@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
assert_rejected_before_docker "64位" \
  env MATECLAW_RUNTIME_BASE_IMAGE="itharbor.sangfor.com/ai-uat/mateclaw-playwright:v1.62.0-noble@sha256:aaaaaaaa"

: > "${TMP_DIR}/docker-calls.log"
env "${common_env[@]}" MATECLAW_RUNTIME_BASE_IMAGE="${runtime_image}" \
  "${UPGRADER}" --activate "${TMP_DIR}/profile.json" >/dev/null \
  || fail "activation should succeed when the real probe succeeds"
[[ "$(readlink "${TMP_DIR}/host/lib64/libseccomp.so.2")" == "libseccomp.so.2.5.6-mateclaw" ]] \
  || fail "successful activation must point the SONAME link to the staged library"
[[ -f "${TMP_DIR}/host/lib64/libseccomp.so.2.3.1" ]] \
  || fail "successful activation must preserve the previous library file"
grep -Fq "run --rm --name mateclaw-libseccomp-probe-" \
  "${TMP_DIR}/docker-calls.log" \
  || fail "the probe must use an exact unique container name"
grep -Fq -- "--security-opt seccomp=${TMP_DIR}/profile.json --entrypoint node ${runtime_image}" \
  "${TMP_DIR}/docker-calls.log" \
  || fail "the probe must use the exact reviewed internal runtime image reference"

ln -sfn libseccomp.so.2.3.1 "${TMP_DIR}/host/lib64/libseccomp.so.2"
if env "${common_env[@]}" FAKE_DOCKER_PROBE_RESULT=1 \
  MATECLAW_RUNTIME_BASE_IMAGE="${runtime_image}" \
  "${UPGRADER}" --activate "${TMP_DIR}/profile.json" >/dev/null 2>&1; then
  fail "activation must fail when the real Docker probe fails"
fi
[[ "$(readlink "${TMP_DIR}/host/lib64/libseccomp.so.2")" == "libseccomp.so.2.3.1" ]] \
  || fail "failed activation must restore the previous SONAME link"

printf 'PASS: libseccomp activation is atomic and rolls back on probe failure\n'
