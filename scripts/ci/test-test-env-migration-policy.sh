#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
pipeline="${root_dir}/Jenkinsfile.test-env"
migration_dir="${root_dir}/mateclaw-server/src/main/resources/db/migration/mysql"

fail() {
  echo "test-test-env-migration-policy: $*" >&2
  exit 1
}

pipeline_value() {
  local name="$1"
  sed -n "s/^[[:space:]]*${name} = '\([^']*\)'$/\1/p" "$pipeline" | head -1
}

runtime_from="$(pipeline_value APPROVED_RUNTIME_FROM)"
runtime_to="$(pipeline_value APPROVED_RUNTIME_TO)"
approved_sha="$(pipeline_value APPROVED_RUNTIME_SHA256)"

[[ "$runtime_from" == "225" ]] || fail "APPROVED_RUNTIME_FROM must be 225"
[[ "$runtime_to" == "228" ]] || fail "APPROVED_RUNTIME_TO must be 228"
[[ "$approved_sha" =~ ^[0-9a-f]{64}$ ]] || fail "APPROVED_RUNTIME_SHA256 is invalid"

actual_sha="$(
  cd "$migration_dir"
  sha256sum V{226..228}__*.sql | sha256sum | awk '{print $1}'
)"
[[ "$actual_sha" == "$approved_sha" ]] \
  || fail "V226-V228 checksum differs from the approved runtime package"

grep -Fq 'APPROVED_RUNTIME_FROM = '\''225'\''' "$pipeline" \
  || fail "runtime migration start is not pinned"
grep -Fq 'APPROVED_RUNTIME_TO = '\''228'\''' "$pipeline" \
  || fail "runtime migration end is not pinned"
grep -Fq "'226 227 228'" "$pipeline" \
  || fail "V225 recovery path does not enumerate V226-V228"
grep -Fq "'227 228'" "$pipeline" \
  || fail "V226 recovery path does not enumerate V227-V228"
grep -Fq "'228'" "$pipeline" \
  || fail "V227 recovery path does not enumerate V228"
grep -Fq '[[ "$deployed_flyway_version" == "$APPROVED_RUNTIME_TO" ]]' "$pipeline" \
  || fail "deployment verification is not pinned to APPROVED_RUNTIME_TO"

echo "test-test-env-migration-policy: PASS (V${runtime_from}->V${runtime_to})"
