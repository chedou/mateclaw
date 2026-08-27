#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PIPELINE="${ROOT_DIR}/Jenkinsfile.test-env"
RELEASE_SCRIPT="${ROOT_DIR}/scripts/release-test-env.sh"
CANDIDATE_BUILDER="${ROOT_DIR}/scripts/ci/build-test-env-candidate-image.sh"

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
if grep -Fq -- '--security-opt seccomp=unconfined' "${PIPELINE}" "${RELEASE_SCRIPT}"; then
  fail "maintenance and release must never disable seccomp"
fi
grep -Fq 'DEPLOY|VERIFY_ONLY|UPGRADE_LIBSECCOMP' "${RELEASE_SCRIPT}" \
  || fail "release helper must accept the controlled maintenance action"
grep -Fq 'UPGRADE_LIBSECCOMP requested' "${RELEASE_SCRIPT}" \
  || fail "release helper must skip deployed-site identity checks for host-only maintenance"

printf 'PASS: Jenkins host maintenance is exact-commit, isolated, test-gated and auditable\n'
