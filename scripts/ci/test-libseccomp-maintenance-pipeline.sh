#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PIPELINE="${ROOT_DIR}/Jenkinsfile.test-env"
RELEASE_SCRIPT="${ROOT_DIR}/scripts/release-test-env.sh"
CANDIDATE_BUILDER="${ROOT_DIR}/scripts/ci/build-test-env-candidate-image.sh"
APPROVED_RUNTIME_BASE_IMAGE='itharbor.sangfor.com/ai-uat/mateclaw-playwright:v1.62.0-noble@sha256:0e5163ed3364179e474b849dbecfaa46a06e21212abe2c67873f706dc609b88e'
APPROVED_BACKEND_BASE_IMAGE='itharbor.sangfor.com/ai-uat/mateclaw-maven:3.9.6-eclipse-temurin-21-alpine@sha256:1750ed0e15881d6b9e11d8657026a492cd29e85e009481bbb1d0d7a0056e42b9'
TMP_DIR="$(mktemp -d)"
trap 'rm -rf -- "$TMP_DIR"' EXIT

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

grep -Fq "choices: ['VERIFY_ONLY', 'UPGRADE_LIBSECCOMP', 'DEPLOY']" "${PIPELINE}" \
  || fail "pipeline must expose the controlled libseccomp maintenance action"
grep -Fq "params.ACTION in ['DEPLOY', 'UPGRADE_LIBSECCOMP']" "${PIPELINE}" \
  || fail "host maintenance and deployment must both require an exact commit"
grep -Fq "stage('Docker18 libseccomp 受控维护')" "${PIPELINE}" \
  || fail "pipeline must have a dedicated host-maintenance stage"
grep -Fq '"$UPGRADER" --all "$PROFILE"' "${PIPELINE}" \
  || fail "maintenance stage must use the reviewed all-in-one upgrader"
grep -Fq 'test-upgrade-libseccomp-docker18.sh' "${PIPELINE}" \
  || fail "pipeline must run the atomic rollback regression test before maintenance"
grep -Fq 'libseccomp-prepare-report.txt' "${PIPELINE}" \
  || fail "pipeline must archive the isolated-build report"
grep -Fq 'libseccomp-activation-report.txt' "${PIPELINE}" \
  || fail "pipeline must archive the activation and probe report"
grep -Fq "expression { params.ACTION != 'UPGRADE_LIBSECCOMP' }" "${PIPELINE}" \
  || fail "image build and migration stages must be skipped during host maintenance"
grep -Fq 'build-test-env-candidate-image.sh' "${PIPELINE}" \
  || fail "pipeline must use the reviewed Docker 18 candidate assembler"
grep -Fq '"$LEGACY_SECCOMP_PROFILE"' "${PIPELINE}" \
  || fail "pipeline must pass the reviewed clone3 seccomp profile to the assembler"
grep -Fq "docker_build_security_mode='LEGACY_REVIEWED_SECCOMP_ASSEMBLY'" "${CANDIDATE_BUILDER}" \
  || fail "assembler must record the reviewed Docker 18 security mode"
if grep -Eq -- 'docker build[^\n]*(--security-opt|security-opt)' "${PIPELINE}" "${CANDIDATE_BUILDER}"; then
  fail "Docker 18.06 docker build must never receive unsupported --security-opt"
fi
grep -Fq 'build-security.txt' "${PIPELINE}" \
  || fail "pipeline must archive image-build seccomp evidence"
auth_cleanup_line="$(grep -nF 'expected_docker_config="$WORKSPACE/.docker-config-${BUILD_NUMBER}"' "${PIPELINE}" | tail -1 | cut -d: -f1)"
image_cleanup_line="$(grep -nF 'docker image rm "$CANDIDATE_IMAGE"' "${PIPELINE}" | tail -1 | cut -d: -f1)"
[[ -n "${auth_cleanup_line}" && -n "${image_cleanup_line}" ]] \
  || fail "post-build Harbor auth or image cleanup is missing"
(( auth_cleanup_line < image_cleanup_line )) \
  || fail "temporary Harbor credentials must be removed before any Docker cleanup"
grep -Fq 'timeout --signal=TERM --kill-after=2s 30' "${PIPELINE}" \
  || fail "post-build Docker cleanup must be bounded"
if grep -Fq -- '--security-opt seccomp=unconfined' "${PIPELINE}" "${RELEASE_SCRIPT}"; then
  fail "maintenance and release must never disable seccomp"
fi
grep -Fq 'DEPLOY|VERIFY_ONLY|UPGRADE_LIBSECCOMP' "${RELEASE_SCRIPT}" \
  || fail "release helper must accept the controlled maintenance action"
grep -Fq 'UPGRADE_LIBSECCOMP requested' "${RELEASE_SCRIPT}" \
  || fail "release helper must skip deployed-site identity checks for host-only maintenance"
grep -Fq 'ACTION|BRANCH|EXPECTED_COMMIT|MATECLAW_RUNTIME_BASE_IMAGE' "${RELEASE_SCRIPT}" \
  || fail "release helper must accept the immutable Playwright Harbor reference"
grep -Fq "APPROVED_RUNTIME_BASE_IMAGE='${APPROVED_RUNTIME_BASE_IMAGE}'" "${RELEASE_SCRIPT}" \
  || fail "release helper must default to the reviewed Playwright digest"
grep -Fq 'runtime_base_image" != "$APPROVED_RUNTIME_BASE_IMAGE' "${RELEASE_SCRIPT}" \
  || fail "release helper must reject any Playwright override that is not the reviewed digest"
grep -Fq "APPROVED_RUNTIME_BASE_IMAGE = '${APPROVED_RUNTIME_BASE_IMAGE}'" "${PIPELINE}" \
  || fail "pipeline must pin the reviewed Playwright digest"
grep -Fq "params.ACTION != 'UPGRADE_LIBSECCOMP' && params.MATECLAW_RUNTIME_BASE_IMAGE != env.APPROVED_RUNTIME_BASE_IMAGE" "${PIPELINE}" \
  || fail "pipeline must reject an unreviewed Playwright digest outside host-only maintenance"
grep -Fq "if (params.ACTION != 'UPGRADE_LIBSECCOMP')" "${PIPELINE}" \
  || fail "Harbor authentication must be skipped during host-only maintenance"
grep -Fq 'HARBOR_LOGIN_SKIPPED: UPGRADE_LIBSECCOMP' "${PIPELINE}" \
  || fail "host-only maintenance must record why Harbor authentication was skipped"
grep -Fq "MATECLAW_BACKEND_BASE_IMAGE = '${APPROVED_BACKEND_BASE_IMAGE}'" "${PIPELINE}" \
  || fail "pipeline must pin the reviewed Maven digest"
grep -Fq "approved_runtime_base_image='${APPROVED_RUNTIME_BASE_IMAGE}'" "${CANDIDATE_BUILDER}" \
  || fail "candidate builder must pin the reviewed Playwright digest"
grep -Fq "approved_backend_base_image='${APPROVED_BACKEND_BASE_IMAGE}'" "${CANDIDATE_BUILDER}" \
  || fail "candidate builder must pin the reviewed Maven digest"

wrong_runtime='itharbor.sangfor.com/ai-uat/mateclaw-playwright:v1.62.0-noble@sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd'
if JENKINS_USER=test JENKINS_API_TOKEN=test MATECLAW_RUNTIME_BASE_IMAGE="$wrong_runtime" \
  "$RELEASE_SCRIPT" --allow-insecure-http --no-wait --no-verify \
  >"$TMP_DIR/wrong-runtime.out" 2>&1; then
  fail "release helper must reject an unreviewed digest before contacting Jenkins"
fi
grep -Fq 'must equal the reviewed immutable Sangfor Harbor Playwright reference' "$TMP_DIR/wrong-runtime.out" \
  || {
    cat "$TMP_DIR/wrong-runtime.out" >&2
    fail "release helper wrong-digest rejection must explain the reviewed-image requirement"
  }

runtime_validation_line="$(grep -nF 'runtime_base_image="${runtime_base_image:-$MATECLAW_RUNTIME_BASE_IMAGE}"' "${RELEASE_SCRIPT}" | head -1 | cut -d: -f1)"
dependency_check_line="$(grep -nF 'command -v python3' "${RELEASE_SCRIPT}" | head -1 | cut -d: -f1)"
[[ -n "$runtime_validation_line" && -n "$dependency_check_line" ]] \
  || fail "release helper runtime validation or dependency check is missing"
(( runtime_validation_line < dependency_check_line )) \
  || fail "release helper must reject an unreviewed runtime before optional CLI dependency checks"

printf 'PASS: Jenkins host maintenance is exact-commit, isolated, test-gated and auditable\n'
