#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PIPELINE="${ROOT_DIR}/Jenkinsfile.test-env"
SIT_CONFIG="${ROOT_DIR}/deploy/environments/sit.env"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

[[ -f "${SIT_CONFIG}" ]] || fail "tracked SIT deployment config is missing"
grep -Fxq 'DEPLOY_ENV=SIT' "${SIT_CONFIG}" \
  || fail "SIT config must declare its exact environment"
grep -Fxq 'MATECLAW_ITDB_ENABLED_SIT=true' "${SIT_CONFIG}" \
  || fail "SIT config must enable the ITDB employee"
grep -Eq '^MATECLAW_ITDB_BASE_URL_SIT=https?://[^[:space:]]+$' "${SIT_CONFIG}" \
  || fail "SIT config must publish the non-secret ITDB base URL"
grep -Eq '^MATECLAW_ITDB_ALLOWED_HOSTS_SIT=[A-Za-z0-9.-]+$' "${SIT_CONFIG}" \
  || fail "SIT config must publish an exact allowed host"
grep -Fxq 'MATECLAW_ITDB_CREDENTIALS_ID_SIT=mateclaw-itdb-sit' "${SIT_CONFIG}" \
  || fail "SIT config must publish the Jenkins credential binding name"
if grep -Eiq '(PASSWORD|USERNAME|ACCESS[_-]?TOKEN|SECRET)[[:space:]]*=' "${SIT_CONFIG}"; then
  fail "tracked environment config must never contain credentials"
fi

grep -Fq "name: 'DEPLOY_ENV'" "${PIPELINE}" \
  || fail "pipeline must require an explicit deployment environment"
grep -Fq "choices: ['SIT']" "${PIPELINE}" \
  || fail "pipeline environment choices must be closed and reviewed"
grep -Fq "credentialsId: 'mateclaw-itdb-sit'" "${PIPELINE}" \
  || fail "pipeline must source SIT credentials from Jenkins Credentials"
grep -Fq 'DEPLOY_ENV_FILE = "${WORKSPACE}/source/deploy/environments/sit.env"' "${PIPELINE}" \
  || fail "pipeline must use the tracked SIT environment config"
grep -Fq 'MATECLAW_ITDB_ENABLED_SIT' "${PIPELINE}" \
  || fail "pipeline must read the suffixed SIT switch"
grep -Fq 'MATECLAW_ITDB_SIT_USR' "${PIPELINE}" \
  || fail "pipeline must use the Jenkins-bound SIT username"
grep -Fq 'MATECLAW_ITDB_SIT_PSW' "${PIPELINE}" \
  || fail "pipeline must use the Jenkins-bound SIT password"
grep -Fq 'echo "deploy_environment=$DEPLOY_ENV"' "${PIPELINE}" \
  || fail "release evidence must record the selected environment"
grep -Fq -- '-e "MATECLAW_ITDB_ENABLED=$itdb_enabled"' "${PIPELINE}" \
  || fail "container must receive the selected ITDB enablement"
grep -Fq -- '-e "MATECLAW_ITDB_USERNAME=$MATECLAW_ITDB_SIT_USR"' "${PIPELINE}" \
  || fail "container must receive the Jenkins-bound ITDB username"
grep -Fq -- '-e "MATECLAW_ITDB_PASSWORD=$MATECLAW_ITDB_SIT_PSW"' "${PIPELINE}" \
  || fail "container must receive the Jenkins-bound ITDB password"
if grep -Eq '(MATECLAW_ITDB_USERNAME|MATECLAW_ITDB_PASSWORD)=.+' "${SIT_CONFIG}"; then
  fail "ITDB credentials must not be committed"
fi

printf 'PASS: SIT config is Git-visible while ITDB credentials remain Jenkins-only\n'
