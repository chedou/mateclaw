#!/usr/bin/env bash
#
# Guance 真源演示：SendMsg 无错误码竖线（U1）。
# 与 troubleshooting-scenario-evidence-smoke.sh 同形状，但断言真源：
#   fixtureMode=false、结论 LOCATED、对照特征命中。
#
#   ./scripts/troubleshooting-guance-sendmsg-demo.sh --preflight
#   ./scripts/troubleshooting-guance-sendmsg-demo.sh
#   ./scripts/troubleshooting-guance-sendmsg-demo.sh --gates
#
set -euo pipefail

BASE_URL="${MATECLAW_BASE_URL:-http://127.0.0.1:18088}"
TOKEN="${MATECLAW_TOKEN:-}"
USERNAME="${MATECLAW_USERNAME:-}"
PASSWORD="${MATECLAW_PASSWORD:-}"
WORKSPACE_ID="${MATECLAW_WORKSPACE_ID:-1}"
SYSTEM="${SCENARIO_SYSTEM:-CSDP}"
SERVICE="${SCENARIO_SERVICE:-csdp-session-service}"
SCENARIO_KEY="${SCENARIO_KEY:-message_send_failed}"
WINDOW="${DEMO_WINDOW:--6h}"
API="${BASE_URL}/api/v1/troubleshooting"

blue() { printf '\033[34m%s\033[0m\n' "$1"; }
red()  { printf '\033[31m%s\033[0m\n' "$1"; }
dim()  { printf '\033[90m%s\033[0m\n' "$1"; }
ok()   { printf '\033[32m  ✓\033[0m %s\n' "$1"; }

gate_failed() {
  local gate="$1" detail="$2" fix="$3"
  red "  ✗ 闸门未通过：${gate}"
  printf '    现象：%s\n' "${detail}"
  printf '    下一步：%s\n' "${fix}"
  exit 1
}

print_gates() {
  blue "U1 Guance SendMsg 真源演示闸门"
  cat <<'GATES'

  0. preflight          spine preview FULL_SPINE_OBSERVED 且对照 failureMatchCount≥1
  1. 选场景开案          NEEDS_INVESTIGATION / INSUFFICIENT_EVIDENCE
  2. 取证前不得确认      confirm → 409
  3. 跑取证              evidence-runs → 非 MISSING 的 guance 证据
  4. LOCATED + 真源      conclusion=LOCATED 且 fixtureMode=false
  5. 取证后可确认        confirm → 200 / CONFIRMED
  6. projection 可读     businessSummary.conclusionType=LOCATED
  7. 重跑被拒            evidence-runs → 409

GATES
}

if [[ "${1:-}" == "--gates" ]]; then
  print_gates
  exit 0
fi

for tool in curl jq; do
  command -v "${tool}" >/dev/null 2>&1 || { red "缺少 ${tool}"; exit 2; }
done

if [[ -z "${TOKEN}" && -n "${USERNAME}" ]]; then
  TOKEN="$(curl -sS -X POST "${BASE_URL}/api/v1/auth/login" \
      -H 'Content-Type: application/json' \
      --data "{\"username\":\"${USERNAME}\",\"password\":\"${PASSWORD}\"}" \
      2>/dev/null | jq -r '.data.token // empty')"
  [[ -n "${TOKEN}" ]] || gate_failed "身份" "登录失败" "检查账号或 MATECLAW_TOKEN"
fi

auth_header=()
[[ -n "${TOKEN}" ]] && auth_header=(-H "Authorization: Bearer ${TOKEN}")

BODY_FILE="$(mktemp -t ts-guance-demo-body.XXXXXX)"
CODE_FILE="$(mktemp -t ts-guance-demo-code.XXXXXX)"
trap 'rm -f "${BODY_FILE}" "${CODE_FILE}"' EXIT

call() {
  local method="$1" path="$2" body="${3:-}"
  local args=(-sS -o "${BODY_FILE}" -w '%{http_code}' -X "${method}"
              -H 'Content-Type: application/json'
              -H "X-Workspace-Id: ${WORKSPACE_ID}" "${auth_header[@]}")
  [[ -n "${body}" ]] && args+=(--data "${body}")
  : > "${BODY_FILE}"
  local code
  code="$(curl "${args[@]}" "${API}${path}" 2>/dev/null || true)"
  printf '%s' "${code: -3}" > "${CODE_FILE}"
  cat "${BODY_FILE}"
}
http_code() { cat "${CODE_FILE}"; }

OCCURRED_AT="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"

preflight() {
  blue "U1 preflight · Guance spine ${WINDOW}"
  local body
  body="$(call POST "/evidence/guance/spine/preview" \
    "{\"system\":\"${SYSTEM}\",\"service\":\"${SERVICE}\",\"searchTerm\":\"${SCENARIO_KEY}\",\"window\":\"${WINDOW}\",\"occurredAt\":\"${OCCURRED_AT}\"}")"
  [[ "$(http_code)" == "200" ]] || gate_failed "preflight" \
    "spine preview HTTP $(http_code)：$(echo "${body}" | jq -r '.msg // .' | head -c 160)" \
    "确认 pilot profile + Guance Key + 网络"
  local stage match fail_match
  stage="$(echo "${body}" | jq -r '.data.stage // empty')"
  match="$(echo "${body}" | jq -r '.data.contrast.failureMatchCount // "0"')"
  fail_match="${match}"
  [[ "${stage}" == "FULL_SPINE_OBSERVED" ]] || gate_failed "preflight" \
    "stage=${stage}，期望 FULL_SPINE_OBSERVED" \
    "加长 DEMO_WINDOW 或检查日志合同"
  [[ "${fail_match}" != "0" && "${fail_match}" != "null" && -n "${fail_match}" ]] \
    || gate_failed "preflight" \
       "对照 failureMatchCount=${fail_match}（特征未命中当前失败）" \
       "按 demo-runbook：重核 message_length 后改 pilot 对照合同"
  ok "spine ${stage} · psId=$(echo "${body}" | jq -r '.data.psId') · failureMatch=${fail_match}"
}

if [[ "${1:-}" == "--preflight" ]]; then
  preflight
  exit 0
fi

blue "MateClaw 演示 · U1 Guance SendMsg 真源竖线"
dim  "服务：${BASE_URL}  workspace=${WORKSPACE_ID}  window=${WINDOW}"
echo

preflight

RUN_MARKER="$(date -u +%H%M%S)-${RANDOM}"
created="$(call POST "/scenarios/${SCENARIO_KEY}/diagnoses" \
  "{\"system\":\"${SYSTEM}\",\"service\":\"${SERVICE}\",\"title\":\"演示 U1 SendMsg ${RUN_MARKER}\",\"severity\":\"P2\",\"impactScope\":\"演示\",\"intakeSource\":\"guance-sendmsg-demo\",\"rawInput\":\"troubleshooting-guance-sendmsg-demo.sh\",\"occurredAt\":\"${OCCURRED_AT}\",\"rehearsal\":false}")"
[[ "$(http_code)" == "200" ]] || gate_failed "选场景开案" \
  "HTTP $(http_code)：$(echo "${created}" | jq -r '.msg // .' | head -c 200)" \
  "确认 Playbook csdp:scenario:message_send_failed 已 approved"
diagnosis_id="$(echo "${created}" | jq -r '.data.diagnosis.diagnosisId // empty')"
status="$(echo "${created}" | jq -r '.data.diagnosis.status')"
conclusion="$(echo "${created}" | jq -r '.data.diagnosis.conclusionType')"
[[ -n "${diagnosis_id}" && "${status}" == "NEEDS_INVESTIGATION" && "${conclusion}" == "INSUFFICIENT_EVIDENCE" ]] \
  || gate_failed "选场景开案" "${conclusion}/${status}" "选场景不得直接下结论"
ok "开案 ${diagnosis_id}"

early="$(call POST "/diagnoses/${diagnosis_id}/confirm")"
[[ "$(http_code)" == "409" ]] || gate_failed "取证前不得确认" "HTTP $(http_code)" "confirm 必须先 409"
ok "取证前 confirm 409"

ran="$(call POST "/diagnoses/${diagnosis_id}/evidence-runs")"
[[ "$(http_code)" == "200" ]] || gate_failed "跑取证" \
  "HTTP $(http_code)：$(echo "${ran}" | jq -r '.msg // .' | head -c 200)" \
  "检查 ScenarioEvidenceRunService / Guance 路由"
after_status="$(echo "${ran}" | jq -r '.data.diagnosis.status')"
after_concl="$(echo "${ran}" | jq -r '.data.diagnosis.conclusionType')"
fixture="$(echo "${ran}" | jq -r '.data.diagnosis.fixtureMode')"
root="$(echo "${ran}" | jq -r '.data.diagnosis.rootCause // empty')"
sources="$(echo "${ran}" | jq -r '[.data.diagnosis.evidence[]?.source] | unique | join(",")')"
[[ "${after_status}" == "READY_FOR_HUMAN" ]] || gate_failed "跑取证" "status=${after_status}" "证据未推进到可人工"
[[ "${after_concl}" == "LOCATED" ]] || gate_failed "LOCATED + 真源" \
  "conclusion=${after_concl}（若 EXCLUDED 对照特征漂了）" \
  "先 --preflight；必要时更新 message_length 合同"
[[ "${fixture}" == "false" ]] || gate_failed "LOCATED + 真源" \
  "fixtureMode=${fixture}，演示要求真源" \
  "确认 pilot routes 为 guance-only，且证据 source 前缀 guance"
ok "LOCATED / fixtureMode=false / rootCause=${root}"
ok "sources=${sources}"

confirmed="$(call POST "/diagnoses/${diagnosis_id}/confirm" '{"actor":"admin","note":"u1 demo"}')"
[[ "$(http_code)" == "200" ]] || gate_failed "取证后可确认" "HTTP $(http_code)" "检查 READY_FOR_HUMAN"
ok "CONFIRMED $(echo "${confirmed}" | jq -r '.data.diagnosis.status')"

proj="$(call GET "/diagnoses/${diagnosis_id}/projection")"
[[ "$(http_code)" == "200" ]] || gate_failed "projection 可读" "HTTP $(http_code)" "检查投影服务"
[[ "$(echo "${proj}" | jq -r '.data.businessSummary.conclusionType')" == "LOCATED" ]] \
  || gate_failed "projection 可读" "businessSummary 结论不是 LOCATED" "核对投影字段"
ok "projection：$(echo "${proj}" | jq -r '.data.businessSummary.headline // .data.businessSummary.conclusionType')"

rerun="$(call POST "/diagnoses/${diagnosis_id}/evidence-runs")"
[[ "$(http_code)" == "409" ]] || gate_failed "重跑被拒" "HTTP $(http_code)" "确认后不得改写"
ok "重跑 409"

echo
blue "U1 通过：真源 SendMsg 竖线可演示。"
dim  "diagnosisId=${diagnosis_id}"
dim  "Web：/troubleshooting?view=detail&diagnosisId=${diagnosis_id}"
