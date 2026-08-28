#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DOCKERFILE="${ROOT_DIR}/mateclaw-server/Dockerfile"
INSTALLER="${ROOT_DIR}/mateclaw-server/docker/install-runtime-dependencies.sh"
KEYRING_B64="${ROOT_DIR}/mateclaw-server/docker/ubuntu-archive-keyring.gpg.b64"
EXPECTED_KEYRING_SHA256="655e378ede8af51ed5f2ffe3669b38f124593abc1aa769c2cc76ef5986a2f835"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

[[ -f "$DOCKERFILE" ]] || fail "missing runtime Dockerfile"
[[ -x "$INSTALLER" ]] || fail "missing executable shared runtime installer"
[[ -f "$KEYRING_B64" ]] || fail "missing vendored Ubuntu archive keyring"

actual_keyring_sha256="$(base64 --decode < "$KEYRING_B64" | sha256sum | awk '{print $1}')"
[[ "$actual_keyring_sha256" == "$EXPECTED_KEYRING_SHA256" ]] \
  || fail "vendored Ubuntu archive keyring digest does not match the reviewed value"

runtime_block="$(awk '
  $0 == "FROM ${MATECLAW_RUNTIME_BASE_IMAGE}" { capture = 1 }
  capture { print }
' "$DOCKERFILE")"
installer_content="$(cat "$INSTALLER")"
[[ -n "$runtime_block" ]] || fail "Playwright runtime stage is missing"
grep -Fq 'ARG MATECLAW_RUNTIME_BASE_IMAGE=itharbor.sangfor.com/ai-uat/mateclaw-playwright:v1.62.0-noble@sha256:0e5163ed3364179e474b849dbecfaa46a06e21212abe2c67873f706dc609b88e' "$DOCKERFILE" \
  || fail "runtime mirror must pin the reviewed internal Harbor digest"
grep -Fq -- 'FROM ${MATECLAW_RUNTIME_BASE_IMAGE}' <<<"$runtime_block" \
  || fail "runtime must consume the required digest-pinned internal Harbor image"
if grep -Fq -- 'mcr.microsoft.com/playwright:' "$DOCKERFILE"; then
  fail "runtime must not fall back to the public Playwright registry"
fi
grep -Fq -- 'COPY mateclaw-server/docker/install-runtime-dependencies.sh /usr/local/sbin/mateclaw-install-runtime-dependencies' <<<"$runtime_block" \
  || fail "runtime stage must copy the shared dependency installer"
grep -Fq -- 'RUN /usr/local/sbin/mateclaw-install-runtime-dependencies /tmp/ubuntu-archive-keyring.gpg.b64' <<<"$runtime_block" \
  || fail "runtime stage must execute the shared dependency installer"
grep -Fq -- 'COPY mateclaw-server/docker/ubuntu-archive-keyring.gpg.b64 /tmp/ubuntu-archive-keyring.gpg.b64' <<<"$runtime_block" \
  || fail "runtime stage must copy the vendored Ubuntu archive keyring"

line_of() {
  local needle="$1"
  grep -nF -- "$needle" "$INSTALLER" | head -n 1 | cut -d: -f1 || true
}

nodesource_line="$(line_of 'rm -f /etc/apt/sources.list.d/nodesource.list')"
keyring_decode_line="$(line_of 'base64 --decode < "$keyring_b64"')"
keyring_checksum_line="$(line_of '| sha256sum --check --strict -')"
keyring_install_line="$(line_of 'install -m 0644 "$decoded_keyring" /usr/share/keyrings/ubuntu-archive-keyring.gpg')"
keyring_trusted_install_line="$(line_of 'install -m 0644 "$decoded_keyring" /etc/apt/trusted.gpg.d/ubuntu-archive-keyring-vendored.gpg')"
keyring_permission_line="$(line_of 'find /usr/share/keyrings /etc/apt/keyrings /etc/apt/trusted.gpg.d')"
apt_update_line="$(line_of 'apt-get update')"
mirror_line="$(line_of "ubuntu_mirror='https://mirrors.aliyun.com/ubuntu/'")"

for required_line in \
  "$nodesource_line" "$keyring_decode_line" "$keyring_checksum_line" \
  "$keyring_install_line" "$keyring_trusted_install_line" \
  "$keyring_permission_line" "$mirror_line" "$apt_update_line"; do
  [[ -n "$required_line" ]] || fail "shared installer is missing a required hardened APT step"
done
(( nodesource_line < apt_update_line )) || fail "NodeSource removal must happen before apt-get update"
(( keyring_decode_line < keyring_checksum_line )) || fail "keyring must be decoded before verification"
(( keyring_checksum_line < keyring_install_line )) || fail "keyring must be verified before installation"
(( keyring_checksum_line < keyring_trusted_install_line )) || fail "keyring must be verified before trusted-store installation"
(( keyring_install_line < apt_update_line )) || fail "keyring must be restored before apt-get update"
(( keyring_trusted_install_line < apt_update_line )) || fail "trusted keyring must be restored before apt-get update"
(( keyring_permission_line < apt_update_line )) || fail "keyring permissions must be repaired before apt-get update"
(( mirror_line < apt_update_line )) || fail "the single HTTPS Ubuntu mirror must be selected before apt-get update"

grep -Fq -- '/etc/apt/sources.list.d/nodesource.sources' "$INSTALLER" \
  || fail "installer must remove both NodeSource source formats"
grep -Fq -- 'chmod a+rx /usr/share/keyrings /etc/apt/keyrings /etc/apt/trusted.gpg.d' "$INSTALLER" \
  || fail "APT keyring directories must be traversable by _apt"
grep -Fq -- '-exec chmod a+r {} +' "$INSTALLER" \
  || fail "keyring repair must grant read access without weakening writes"
grep -Fq -- "$EXPECTED_KEYRING_SHA256" "$INSTALLER" \
  || fail "installer must pin the decoded Ubuntu keyring digest"
grep -Fq -- 'ubuntu-keyring_2026.08.18_all.deb' "$INSTALLER" \
  || fail "Ubuntu archive keyring source package must be documented"
grep -Fq -- 'Acquire::Retries=5' "$INSTALLER" \
  || fail "APT transport errors must use bounded retries"
grep -Fq -- 'Acquire::https::Timeout=30' "$INSTALLER" \
  || fail "APT HTTPS operations must have a bounded timeout"
grep -Fq -- 'azure\\.archive\\.ubuntu\\.com|archive\\.ubuntu\\.com|security\\.ubuntu\\.com' "$INSTALLER" \
  || fail "all inherited Ubuntu archive endpoints must be normalized to one mirror"
grep -Fq -- 'fec10bd81d9ce809a5c11c6227a367611dd0e2589afebb41d46aefb350be8f40' "$INSTALLER" \
  || fail "Ubuntu archive keyring source package digest must be documented"
grep -Fq -- "dpkg-query --show --showformat='\${Package}=\${Version}\\n'" "$INSTALLER" \
  || fail "installer must record the exact runtime package manifest"

for inspected_content in "$runtime_block" "$installer_content"; do
  if grep -Fq -- 'APT::Sandbox::User=root' <<<"$inspected_content"; then
    fail "runtime must keep APT's default _apt sandbox"
  fi
  for insecure_bypass in \
    allow-unauthenticated trusted=yes 'Trusted: yes' AllowInsecureRepositories \
    AllowDowngradeToInsecureRepositories 'APT::Get::AllowUnauthenticated'; do
    if grep -Fqi -- "$insecure_bypass" <<<"$inspected_content"; then
      fail "runtime must not bypass APT signature verification: $insecure_bypass"
    fi
  done
  for unreviewed_key_path in apt-key keyserver.ubuntu.com; do
    if grep -Fqi -- "$unreviewed_key_path" <<<"$inspected_content"; then
      fail "runtime must not import an unpinned key: $unreviewed_key_path"
    fi
  done
  if grep -Fq -- '|| true' <<<"$inspected_content"; then
    fail "runtime must not ignore a failed trust or package operation"
  fi
done

printf 'PASS: Dockerfile and Docker 18 assembly share one hardened runtime installer\n'
