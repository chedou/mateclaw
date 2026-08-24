#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DOCKERFILE="${ROOT_DIR}/mateclaw-server/Dockerfile"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

[[ -f "${DOCKERFILE}" ]] || fail "missing runtime Dockerfile"

runtime_block="$(awk '
  /^FROM mcr\.microsoft\.com\/playwright:/ { capture = 1 }
  capture { print }
' "${DOCKERFILE}")"

[[ -n "${runtime_block}" ]] || fail "Playwright runtime stage is missing"

line_of() {
  local needle="$1"
  grep -nF -- "${needle}" <<<"${runtime_block}" | head -n 1 | cut -d: -f1 || true
}

nodesource_line="$(line_of 'rm -f /etc/apt/sources.list.d/nodesource.list')"
keyring_line="$(line_of 'find /usr/share/keyrings /etc/apt/keyrings /etc/apt/trusted.gpg.d')"
apt_update_line="$(line_of '&& apt-get update')"

[[ -n "${nodesource_line}" ]] \
  || fail "runtime stage must remove the unused NodeSource repository"
[[ -n "${keyring_line}" ]] \
  || fail "runtime stage must make inherited APT keyrings readable"
[[ -n "${apt_update_line}" ]] \
  || fail "runtime stage must update APT indexes"
(( nodesource_line < apt_update_line )) \
  || fail "NodeSource removal must happen before apt-get update"
(( keyring_line < apt_update_line )) \
  || fail "keyring permission repair must happen before apt-get update"

grep -Fq -- '/etc/apt/sources.list.d/nodesource.sources' <<<"${runtime_block}" \
  || fail "runtime stage must remove both NodeSource source formats"
grep -Fq -- 'chmod a+rx /usr/share/keyrings /etc/apt/keyrings /etc/apt/trusted.gpg.d' <<<"${runtime_block}" \
  || fail "APT keyring directories must be traversable by the _apt user"
grep -Fq -- '-exec chmod a+r {} +' <<<"${runtime_block}" \
  || fail "keyring repair must grant read access without weakening write permissions"

for insecure_bypass in allow-unauthenticated trusted=yes AllowInsecureRepositories; do
  if grep -Fqi -- "${insecure_bypass}" <<<"${runtime_block}"; then
    fail "runtime stage must not bypass APT signature verification: ${insecure_bypass}"
  fi
done

printf 'PASS: runtime APT sources are hardened before apt-get update\n'
