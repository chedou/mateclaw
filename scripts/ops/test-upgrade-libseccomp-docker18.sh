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
)

env "${common_env[@]}" "${UPGRADER}" --activate "${TMP_DIR}/profile.json" >/dev/null \
  || fail "activation should succeed when the real probe succeeds"
[[ "$(readlink "${TMP_DIR}/host/lib64/libseccomp.so.2")" == "libseccomp.so.2.5.6-mateclaw" ]] \
  || fail "successful activation must point the SONAME link to the staged library"
[[ -f "${TMP_DIR}/host/lib64/libseccomp.so.2.3.1" ]] \
  || fail "successful activation must preserve the previous library file"

ln -sfn libseccomp.so.2.3.1 "${TMP_DIR}/host/lib64/libseccomp.so.2"
if env "${common_env[@]}" FAKE_DOCKER_PROBE_RESULT=1 \
  "${UPGRADER}" --activate "${TMP_DIR}/profile.json" >/dev/null 2>&1; then
  fail "activation must fail when the real Docker probe fails"
fi
[[ "$(readlink "${TMP_DIR}/host/lib64/libseccomp.so.2")" == "libseccomp.so.2.3.1" ]] \
  || fail "failed activation must restore the previous SONAME link"

printf 'PASS: libseccomp activation is atomic and rolls back on probe failure\n'
