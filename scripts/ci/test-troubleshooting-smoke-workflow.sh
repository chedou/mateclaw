#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORKFLOW="${ROOT_DIR}/.github/workflows/troubleshooting-smoke.yml"
MAVEN_SETTINGS="${ROOT_DIR}/mateclaw-server/settings.xml"
SMOKE_SCRIPT="${ROOT_DIR}/scripts/troubleshooting-smoke.sh"
MISS_PATH_SCRIPT="${ROOT_DIR}/scripts/troubleshooting-miss-path-smoke.sh"
SCENARIO_SCRIPT="${ROOT_DIR}/scripts/troubleshooting-scenario-smoke.sh"
EVIDENCE_SCRIPT="${ROOT_DIR}/scripts/troubleshooting-scenario-evidence-smoke.sh"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

assert_contains() {
  local needle="$1"
  grep -Fq -- "${needle}" "${WORKFLOW}" \
    || fail "workflow must contain: ${needle}"
}

assert_not_contains() {
  local needle="$1"
  if grep -Fq -- "${needle}" "${WORKFLOW}"; then
    fail "workflow must not contain: ${needle}"
  fi
}

assert_smoke_contains() {
  local needle="$1"
  grep -Fq -- "${needle}" "${SMOKE_SCRIPT}" \
    || fail "smoke script must contain: ${needle}"
}

assert_miss_path_contains() {
  local needle="$1"
  grep -Fq -- "${needle}" "${MISS_PATH_SCRIPT}" \
    || fail "miss-path smoke script must contain: ${needle}"
}

assert_scenario_contains() {
  local needle="$1"
  grep -Fq -- "${needle}" "${SCENARIO_SCRIPT}" \
    || fail "scenario smoke script must contain: ${needle}"
}

assert_evidence_contains() {
  local needle="$1"
  grep -Fq -- "${needle}" "${EVIDENCE_SCRIPT}" \
    || fail "scenario evidence smoke script must contain: ${needle}"
}

assert_order() {
  local earlier="$1" later="$2"
  local earlier_line later_line
  earlier_line="$(grep -nF -- "${earlier}" "${WORKFLOW}" | head -n 1 | cut -d: -f1)"
  later_line="$(grep -nF -- "${later}" "${WORKFLOW}" | head -n 1 | cut -d: -f1)"
  [[ -n "${earlier_line}" && -n "${later_line}" ]] \
    || fail "cannot compare missing workflow steps: ${earlier} -> ${later}"
  (( earlier_line < later_line )) \
    || fail "workflow must run '${earlier}' before '${later}'"
}

[[ -f "${WORKFLOW}" ]] || fail "missing workflow: .github/workflows/troubleshooting-smoke.yml"
python3 -c 'import sys, xml.etree.ElementTree as ET; ET.parse(sys.argv[1])' "${MAVEN_SETTINGS}" \
  || fail "Docker Maven settings must be well-formed XML"

assert_contains "pull_request:"
assert_contains "push:"
assert_contains "workflow_dispatch:"
assert_contains "actions/checkout@v6"
assert_contains "actions/setup-java@v5"
assert_contains "java-version: '21'"
assert_not_contains "--settings mateclaw-server/settings.xml"
assert_contains "-pl mateclaw-plugin-api"
assert_contains "-am -DskipTests install"
assert_contains "spring-boot:run"
assert_contains "dev,troubleshooting-demo"
assert_contains "{1..60}"
assert_contains "sleep 2"
assert_contains "/sops/csdp/IM1010"
assert_contains '[[ "${playbook_status}" == "approved" ]]'
assert_contains "./scripts/troubleshooting-smoke.sh"
assert_contains "MATECLAW_USERNAME: admin"
assert_contains "MATECLAW_PASSWORD: admin123"
assert_contains "300"
assert_contains 'GITHUB_STEP_SUMMARY'
assert_contains "if: always()"
assert_contains "actions/upload-artifact@v7"
assert_contains "kill"
assert_order "-pl mateclaw-plugin-api" "spring-boot:run"

# The owner preparation queue is committed evidence, not a hand-maintained
# spreadsheet.  Any change to L0, the frozen selector inventory, recorded
# seeds, or the server-owned target catalog must make CI regenerate-or-fail
# before we spend the T7 intranet window on stale work.
assert_contains "python3 -m unittest docs/intelligent-troubleshooting/l0/test_t7_target_preparation.py"
assert_contains "python3 docs/intelligent-troubleshooting/l0/t7_target_preparation.py --check"
assert_contains "python3 -m unittest docs/intelligent-troubleshooting/l0/test_t7_owner_contract_intake.py"
assert_contains "python3 docs/intelligent-troubleshooting/l0/t7_owner_contract_intake.py --check"
assert_order "t7_target_preparation.py --check" "-pl mateclaw-plugin-api"
assert_order "t7_owner_contract_intake.py --check" "-pl mateclaw-plugin-api"

assert_smoke_contains 'SMOKE_SERVICE:-csp-rpc-msg'
assert_smoke_contains 'SMOKE_ERROR_CODE:-IM1010'

# The learning loop must stay in CI. A green diagnosis loop over a dead
# knowledge loop is not a green build: blueprint §11.1 names the no-error-code
# case as the one that must pass first, and it is the only path that produces
# new Playbooks. Dropping this step would silently return knowledge supply to
# "a human hand-writes it from a spreadsheet".
assert_contains "./scripts/troubleshooting-miss-path-smoke.sh"
assert_order "./scripts/troubleshooting-smoke.sh" "./scripts/troubleshooting-miss-path-smoke.sh"
[[ -x "${MISS_PATH_SCRIPT}" ]] || fail "miss-path smoke script must be executable"
assert_miss_path_contains 'SMOKE_SEARCH_TERM:-message_send_failed'
# The reverse assertion: producing knowledge is easy, producing knowledge that
# cannot be mistaken for authority is the hard part.
assert_miss_path_contains 'NOT_ELIGIBLE'
assert_miss_path_contains 'CANDIDATE_REUSED'
# The online lane for a no-error-code fault. Without it the miss path proves
# only that knowledge can be produced, while the reporter still gets nothing.
assert_miss_path_contains '/scenarios/${SCENARIO_KEY}/diagnoses'
assert_miss_path_contains 'SCENARIO_PLAYBOOK'

# One complete case must stay in CI. Without it the product's central
# guarantee is only demonstrated in its refusing half (POST /execute -> 409);
# the affirmative half — approval moves the action to APPROVED_NOT_EXECUTED
# while executionStatus stays BLOCKED — has no coverage at the HTTP boundary.
assert_contains "./scripts/troubleshooting-scenario-smoke.sh"
assert_order "./scripts/troubleshooting-miss-path-smoke.sh" "./scripts/troubleshooting-scenario-smoke.sh"
[[ -x "${SCENARIO_SCRIPT}" ]] || fail "scenario smoke script must be executable"
assert_scenario_contains 'APPROVED_NOT_EXECUTED'
assert_scenario_contains 'executionStatus=${execution_status}，期望仍然是 BLOCKED'

# The symptom lane. Without it, "报障人在线上能不能拿到结论" is proven for
# exactly one scenario — deployment topology, which has its own probe endpoint —
# and the general path can regress back to "waits forever" unnoticed.
assert_contains "./scripts/troubleshooting-scenario-evidence-smoke.sh"
assert_order "./scripts/troubleshooting-scenario-smoke.sh" \
  "./scripts/troubleshooting-scenario-evidence-smoke.sh"
[[ -x "${EVIDENCE_SCRIPT}" ]] || fail "scenario evidence smoke script must be executable"
assert_evidence_contains '/diagnoses/${diagnosis_id}/evidence-runs'
# The gate that makes the other six mean anything: confirm must be refused
# BEFORE the evidence runs. Drop it and the script passes on a system that was
# never stuck, which is indistinguishable from a system that was fixed.
assert_evidence_contains '取证前不得确认'
# Both directions of the citation check. A one-sided version passes on an empty
# list, and an empty list is what a wrong field name returns.
assert_evidence_contains 'evidenceCitations'

# The T7 window preflight and its own regression. The window is the single
# most expensive, least repeatable step left (owner + intranet + controlled
# key), and a preflight whose "ready" path was never exercised would be worse
# than none — it would send someone in on a false green.
assert_contains "./scripts/ci/test-troubleshooting-t7-preflight.sh"
[[ -x "${ROOT_DIR}/scripts/troubleshooting-t7-preflight.sh" ]] \
  || fail "T7 preflight must be executable"
[[ -x "${ROOT_DIR}/scripts/ci/test-troubleshooting-t7-preflight.sh" ]] \
  || fail "T7 preflight regression must be executable"
# Read-only by construction: a preflight that could submit a credential is
# worse than no preflight. (`grep -q | grep -v` would have been vacuous — -q
# prints nothing, so the second grep receives an empty stream and never fires.)
mutating="$(grep -nE -- '-X[[:space:]]*"?(POST|PUT|PATCH|DELETE)' \
  "${ROOT_DIR}/scripts/troubleshooting-t7-preflight.sh" \
  | grep -v 'auth/login' || true)"
if [[ -n "${mutating}" ]]; then
  fail "T7 preflight must stay read-only apart from login: ${mutating}"
fi

if grep -Eqi 'guance|fixtureMode[[:space:]]*:[[:space:]]*false' "${WORKFLOW}"; then
  fail "workflow must stay fixture-only and must not configure Guance"
fi

if grep -Fq -- '<mirrorOf>*</mirrorOf>' "${MAVEN_SETTINGS}"; then
  fail "Docker Maven settings must not force a repository-wide mirror"
fi

printf 'PASS: troubleshooting smoke workflow contract\n'
