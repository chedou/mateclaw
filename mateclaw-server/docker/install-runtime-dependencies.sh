#!/usr/bin/env bash

set -euo pipefail

keyring_b64="${1:-/tmp/ubuntu-archive-keyring.gpg.b64}"
decoded_keyring='/tmp/ubuntu-archive-keyring.gpg'
expected_keyring_sha256='655e378ede8af51ed5f2ffe3669b38f124593abc1aa769c2cc76ef5986a2f835'
package_manifest="${MATECLAW_RUNTIME_PACKAGE_MANIFEST:-/app/runtime-package-manifest.txt}"

[[ "$(id -u)" -eq 0 ]] || {
  printf 'runtime dependency installer must run as root\n' >&2
  exit 1
}
[[ -r "$keyring_b64" ]] || {
  printf 'vendored Ubuntu archive keyring is not readable: %s\n' "$keyring_b64" >&2
  exit 1
}

# The source is Ubuntu's ubuntu-keyring_2026.08.18_all.deb package, whose
# reviewed package SHA-256 is
# fec10bd81d9ce809a5c11c6227a367611dd0e2589afebb41d46aefb350be8f40.
rm -f /etc/apt/sources.list.d/nodesource.list \
  /etc/apt/sources.list.d/nodesource.sources
base64 --decode < "$keyring_b64" > "$decoded_keyring"
printf '%s  %s\n' "$expected_keyring_sha256" "$decoded_keyring" \
  | sha256sum --check --strict -
install -m 0644 "$decoded_keyring" /usr/share/keyrings/ubuntu-archive-keyring.gpg
install -m 0644 "$decoded_keyring" /etc/apt/trusted.gpg.d/ubuntu-archive-keyring-vendored.gpg
rm -f "$decoded_keyring"
chmod a+rx /usr/share/keyrings /etc/apt/keyrings /etc/apt/trusted.gpg.d
find /usr/share/keyrings /etc/apt/keyrings /etc/apt/trusted.gpg.d \
  -type f \( -name '*.gpg' -o -name '*.asc' \) -exec chmod a+r {} +

apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
  openjdk-21-jre-headless \
  fonts-noto-cjk \
  fonts-noto-color-emoji \
  poppler-utils \
  tesseract-ocr \
  tesseract-ocr-chi-sim \
  tzdata \
  python3-pip \
  python-is-python3 \
  python3-dev \
  build-essential
rm -rf /var/lib/apt/lists/*

# This is an application container, not a mutable host Python installation.
rm -f /usr/lib/python3*/EXTERNALLY-MANAGED

install -d -o root -g root -m 0755 "$(dirname "$package_manifest")"
dpkg-query --show --showformat='${Package}=${Version}\n' \
  openjdk-21-jre-headless \
  fonts-noto-cjk \
  fonts-noto-color-emoji \
  poppler-utils \
  tesseract-ocr \
  tesseract-ocr-chi-sim \
  tzdata \
  python3-pip \
  python-is-python3 \
  python3-dev \
  build-essential \
  | LC_ALL=C sort > "$package_manifest"
chown root:root "$package_manifest"
chmod 0644 "$package_manifest"
