#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DETECTOR="${ROOT_DIR}/scripts/ci/detect-libseccomp-version.sh"
TMP_DIR="$(mktemp -d)"
trap 'find "${TMP_DIR}" -mindepth 1 -delete; rmdir "${TMP_DIR}"' EXIT

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

mkdir -p "${TMP_DIR}/bin"
printf '%s\n' \
  '#!/usr/bin/env bash' \
  'case "${FAKE_LIBSECCOMP_RESULT:-ok}" in' \
  '  ok) printf "2.5.6\n" ;;' \
  '  malformed) printf "not-a-version\n" ;;' \
  '  fail) exit 1 ;;' \
  'esac' \
  > "${TMP_DIR}/bin/python3"
chmod +x "${TMP_DIR}/bin/python3"

actual="$(PATH="${TMP_DIR}/bin:/usr/bin:/bin" "${DETECTOR}")" \
  || fail "detector must read the version exported by the loaded library"
[[ "${actual}" == "2.5.6" ]] || fail "unexpected detected version: ${actual}"

if PATH="${TMP_DIR}/bin:/usr/bin:/bin" FAKE_LIBSECCOMP_RESULT=malformed \
  "${DETECTOR}" >/dev/null 2>&1; then
  fail "detector must reject malformed versions"
fi

if PATH="${TMP_DIR}/bin:/usr/bin:/bin" FAKE_LIBSECCOMP_RESULT=fail \
  "${DETECTOR}" >/dev/null 2>&1; then
  fail "detector must propagate loader failures"
fi

printf 'PASS: loaded libseccomp version detection is fail-closed\n'
