#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DOCKERFILE="${ROOT_DIR}/mateclaw-server/Dockerfile"
KEYRING_B64="${ROOT_DIR}/mateclaw-server/docker/ubuntu-archive-keyring.gpg.b64"
EXPECTED_KEYRING_SHA256="80a36b0a6de2f69f49d2df75ef473ccde121e9e190b9ea01d20a4f63778d5c31"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

[[ -f "${DOCKERFILE}" ]] || fail "missing runtime Dockerfile"
[[ -f "${KEYRING_B64}" ]] || fail "missing vendored Ubuntu archive keyring"

actual_keyring_sha256="$(base64 --decode < "${KEYRING_B64}" | sha256sum | awk '{print $1}')"
[[ "${actual_keyring_sha256}" == "${EXPECTED_KEYRING_SHA256}" ]] \
  || fail "vendored Ubuntu archive keyring digest does not match the reviewed value"

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
keyring_copy_line="$(line_of 'COPY mateclaw-server/docker/ubuntu-archive-keyring.gpg.b64 /tmp/ubuntu-archive-keyring.gpg.b64')"
keyring_decode_line="$(line_of 'base64 --decode < /tmp/ubuntu-archive-keyring.gpg.b64')"
keyring_checksum_line="$(line_of '| sha256sum --check --strict -')"
keyring_install_line="$(line_of 'install -m 0644 /tmp/ubuntu-archive-keyring.gpg /usr/share/keyrings/ubuntu-archive-keyring.gpg')"
keyring_permission_line="$(line_of 'find /usr/share/keyrings /etc/apt/keyrings /etc/apt/trusted.gpg.d')"
apt_update_line="$(line_of '&& apt-get update')"

[[ -n "${nodesource_line}" ]] \
  || fail "runtime stage must remove the unused NodeSource repository"
[[ -n "${keyring_copy_line}" ]] \
  || fail "runtime stage must copy the vendored Ubuntu archive keyring"
[[ -n "${keyring_decode_line}" ]] \
  || fail "runtime stage must decode the vendored Ubuntu archive keyring"
[[ -n "${keyring_checksum_line}" ]] \
  || fail "runtime stage must verify the pinned archive keyring digest"
[[ -n "${keyring_install_line}" ]] \
  || fail "runtime stage must restore the archive keyring used by ubuntu.sources"
[[ -n "${keyring_permission_line}" ]] \
  || fail "runtime stage must make inherited APT keyrings readable"
[[ -n "${apt_update_line}" ]] \
  || fail "runtime stage must update APT indexes"
(( nodesource_line < apt_update_line )) \
  || fail "NodeSource removal must happen before apt-get update"
(( keyring_copy_line < keyring_decode_line )) \
  || fail "archive keyring must be copied before it is decoded"
(( keyring_decode_line < keyring_checksum_line )) \
  || fail "archive keyring decoding must happen before checksum verification"
(( keyring_checksum_line < keyring_install_line )) \
  || fail "archive keyring must be verified before installation"
(( keyring_install_line < apt_update_line )) \
  || fail "archive keyring must be restored before apt-get update"
(( keyring_permission_line < apt_update_line )) \
  || fail "keyring permission repair must happen before apt-get update"

grep -Fq -- '/etc/apt/sources.list.d/nodesource.sources' <<<"${runtime_block}" \
  || fail "runtime stage must remove both NodeSource source formats"
grep -Fq -- 'chmod a+rx /usr/share/keyrings /etc/apt/keyrings /etc/apt/trusted.gpg.d' <<<"${runtime_block}" \
  || fail "APT keyring directories must be traversable by the _apt user"
grep -Fq -- '-exec chmod a+r {} +' <<<"${runtime_block}" \
  || fail "keyring repair must grant read access without weakening write permissions"
grep -Fq -- 'echo "80a36b0a6de2f69f49d2df75ef473ccde121e9e190b9ea01d20a4f63778d5c31  /tmp/ubuntu-archive-keyring.gpg"' <<<"${runtime_block}" \
  || fail "Ubuntu archive keyring digest must be fixed in the build instruction"

for insecure_bypass in \
  allow-unauthenticated \
  trusted=yes \
  'Trusted: yes' \
  AllowInsecureRepositories \
  AllowDowngradeToInsecureRepositories \
  'APT::Get::AllowUnauthenticated'; do
  if grep -Fqi -- "${insecure_bypass}" <<<"${runtime_block}"; then
    fail "runtime stage must not bypass APT signature verification: ${insecure_bypass}"
  fi
done

if grep -Fq -- '|| true' <<<"${runtime_block}"; then
  fail "runtime stage must not ignore a failed trust or package operation"
fi

for unreviewed_key_path in apt-key keyserver.ubuntu.com; do
  if grep -Fqi -- "${unreviewed_key_path}" <<<"${runtime_block}"; then
    fail "runtime stage must not import an unpinned key: ${unreviewed_key_path}"
  fi
done

printf 'PASS: runtime APT sources are hardened before apt-get update\n'
