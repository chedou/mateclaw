#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
pipeline="${root_dir}/Jenkinsfile.test-env"
migration_dir="${root_dir}/mateclaw-server/src/main/resources/db/migration/mysql"
runtime_installer="${root_dir}/mateclaw-server/docker/install-runtime-dependencies.sh"
candidate_builder="${root_dir}/scripts/ci/build-test-env-candidate-image.sh"

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
foundation_sha="$(pipeline_value APPROVED_FOUNDATION_SHA256)"
formal_diagnosis_sha="$(pipeline_value APPROVED_FORMAL_DIAGNOSIS_SHA256)"
itdb_sha="$(pipeline_value APPROVED_ITDB_SHA256)"

[[ "$runtime_from" == "225" ]] || fail "APPROVED_RUNTIME_FROM must be 225"
[[ "$runtime_to" == "228" ]] || fail "APPROVED_RUNTIME_TO must be 228"
[[ "$approved_sha" =~ ^[0-9a-f]{64}$ ]] || fail "APPROVED_RUNTIME_SHA256 is invalid"

actual_sha="$(
  cd "$migration_dir"
  sha256sum V{226..228}__*.sql | sha256sum | awk '{print $1}'
)"
[[ "$actual_sha" == "$approved_sha" ]] \
  || fail "V226-V228 checksum differs from the approved runtime package"
actual_foundation_sha="$(cd "$migration_dir" && sha256sum V{205..217}__*.sql | sha256sum | awk '{print $1}')"
actual_formal_diagnosis_sha="$(cd "$migration_dir" && sha256sum V{218..223}__*.sql | sha256sum | awk '{print $1}')"
actual_itdb_sha="$(cd "$migration_dir" && sha256sum V{224..225}__*.sql | sha256sum | awk '{print $1}')"
[[ "$actual_foundation_sha" == "$foundation_sha" ]] \
  || fail "V205-V217 checksum differs from the approved foundation package"
[[ "$actual_formal_diagnosis_sha" == "$formal_diagnosis_sha" ]] \
  || fail "V218-V223 checksum differs from the approved formal-diagnosis package"
[[ "$actual_itdb_sha" == "$itdb_sha" ]] \
  || fail "V224-V225 checksum differs from the approved ITDB package"

if grep -Eq 'MYSQL_CLIENT_IMAGE|mysqldump|--entrypoint mysql|flyway_schema_history|\.mysql-client-' "$pipeline"; then
  fail "pipeline must defer every database connection and Flyway operation to application startup"
fi

grep -Fq '[[ "$APPROVED_FOUNDATION_TO" == "$APPROVED_FORMAL_DIAGNOSIS_FROM" ]]' "$pipeline" \
  || fail "approved migration packages are not checked for continuity"
grep -Fq '[[ "$APPROVED_FORMAL_DIAGNOSIS_TO" == "$APPROVED_ITDB_FROM" ]]' "$pipeline" \
  || fail "formal-diagnosis and ITDB migration packages are not checked for continuity"
grep -Fq '[[ "$APPROVED_ITDB_TO" == "$APPROVED_RUNTIME_FROM" ]]' "$pipeline" \
  || fail "ITDB and runtime migration packages are not checked for continuity"
grep -Fq '[[ "$repo_max_version" == "$APPROVED_RUNTIME_TO" ]]' "$pipeline" \
  || fail "repository migration endpoint is not pinned to APPROVED_RUNTIME_TO"
grep -Fq 'database_connection=DEFERRED_TO_APPLICATION_STARTUP' "$pipeline" \
  || fail "pipeline does not explicitly defer the database connection to application startup"
grep -Fq 'flyway_execution=APPLICATION_MANAGED' "$pipeline" \
  || fail "pipeline does not record application-managed Flyway execution"
grep -Fq 'MATECLAW_FLYWAY_AUTO_REPAIR' "$pipeline" \
  || fail "pipeline must reject enabled Flyway auto-repair"
grep -Fq -- "-e 'MATECLAW_FLYWAY_AUTO_REPAIR=false'" "$pipeline" \
  || fail "application startup must explicitly force Flyway repair off"

for forbidden in 'mysql:8.0' 'DB_BACKUP' 'mysql_backup='; do
  if grep -Fq -- "$forbidden" "$pipeline"; then
    fail "pipeline must not contain database-client operation: $forbidden"
  fi
done
if grep -Eq -- 'command -v (mysql|mariadb)|mysql-server|mariadb-client' "$pipeline"; then
  fail "pipeline must not depend on a host or container database client"
fi
if grep -Eq -- '^[[:space:]]+mysql-client([[:space:]]*\\)?$|mysql-client-(core-)?8\.0' "$runtime_installer"; then
  fail "application runtime must not package a database client for Jenkins"
fi
if grep -Eq -- '(^|[[:space:]])(mysql|mysqldump)[[:space:]]+--version' "$candidate_builder"; then
  fail "candidate image verification must not require database client binaries"
fi
grep -Fq 'CANDIDATE_IMAGE_ID="$(cat candidate-image-id.txt)"' "$pipeline" \
  || fail "pipeline must resolve the immutable candidate application image ID"
grep -Fq '[[ "$candidate_tag_image_id" == "$CANDIDATE_IMAGE_ID" ]]' "$pipeline" \
  || fail "candidate image tag drift must fail closed"
grep -Fq 'docker tag "$CANDIDATE_IMAGE_ID" "$TARGET_IMAGE"' "$pipeline" \
  || fail "release tag must be created from the immutable candidate image ID"

candidate_validation_line="$(grep -nF 'candidate_tag_image_id=' "$pipeline" | tail -1 | cut -d: -f1 || true)"
old_stop_line="$(grep -nF 'docker stop -t 30 "$CONTAINER"' "$pipeline" | head -1 | cut -d: -f1 || true)"
[[ -n "$candidate_validation_line" && -n "$old_stop_line" ]] \
  || fail "candidate image identity or old-container stop step is missing"
(( candidate_validation_line < old_stop_line )) \
  || fail "candidate image identity must be validated before stopping the old application"

[[ "$(grep -Fc -- '--env-file "$ENV_FILE"' "$pipeline")" -eq 1 ]] \
  || fail "application container must receive ENV_FILE exactly once"
grep -Fq 'sha256sum "$ENV_FILE"' "$pipeline" \
  || fail "ENV_FILE immutability is not verified"
grep -Fq 'env-file-sha256.txt' "$pipeline" \
  || fail "ENV_FILE digest is not carried from preflight to cutover"
if grep -Eq -- '(>|>>)[[:space:]]*"?\$ENV_FILE|sed[[:space:]]+-i[^\n]*ENV_FILE' "$pipeline"; then
  fail "pipeline must never rewrite ENV_FILE"
fi

grep -Fq 'rollback() {' "$pipeline" || fail "container rollback handler is missing"
grep -Fq 'docker rename "$ROLLBACK_CONTAINER" "$CONTAINER"' "$pipeline" \
  || fail "old container is not restored on failed cutover"
grep -Fq 'curl -fsS --max-time 5 "$HEALTH_URL" > deployed-health.json' "$pipeline" \
  || fail "deployed application health is not verified"
grep -Fq 'assert_deployed_commit deployed-info.json' "$pipeline" \
  || fail "deployed commit identity is not verified"
grep -Fq "grep -oE '[0-9a-f]{40}'" "$pipeline" \
  || fail "deployed commit identity must use a Groovy-safe exact SHA parser"

echo "test-test-env-migration-policy: PASS (static V205-V228; application-managed Flyway)"
