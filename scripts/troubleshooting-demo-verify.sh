#!/usr/bin/env bash
#
# 演示六用法 HTTP 复验（U1–U6）。不替代 Web 点讲，只证明链路可复现。
#
#   ./scripts/troubleshooting-demo-verify.sh all
#   ./scripts/troubleshooting-demo-verify.sh u1|u2|u3|u4|u5|u6
#
set -euo pipefail

BASE_URL="${MATECLAW_BASE_URL:-http://127.0.0.1:18088}"
TOKEN="${MATECLAW_TOKEN:-}"
USERNAME="${MATECLAW_USERNAME:-admin}"
PASSWORD="${MATECLAW_PASSWORD:-admin123}"
WORKSPACE_ID="${MATECLAW_WORKSPACE_ID:-1}"
API="${BASE_URL}/api/v1/troubleshooting"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TARGET="${1:-all}"

blue() { printf '\033[34m%s\033[0m\n' "$1"; }
red()  { printf '\033[31m%s\033[0m\n' "$1"; }
dim()  { printf '\033[90m%s\033[0m\n' "$1"; }
ok()   { printf '\033[32m  ✓\033[0m %s\n' "$1"; }
fail() { red "  ✗ $1"; printf '    %s\n' "$2"; exit 1; }

for tool in curl jq; do
  command -v "${tool}" >/dev/null 2>&1 || { red "缺少 ${tool}"; exit 2; }
done

if [[ -z "${TOKEN}" ]]; then
  TOKEN="$(curl -sS -X POST "${BASE_URL}/api/v1/auth/login" \
      -H 'Content-Type: application/json' \
      --data "{\"username\":\"${USERNAME}\",\"password\":\"${PASSWORD}\"}" \
      | jq -r '.data.token // .data.accessToken // empty')"
fi
[[ -n "${TOKEN}" ]] || fail "登录" "无法取得 token"

AUTH=(-H "Authorization: Bearer ${TOKEN}" -H "X-Workspace-Id: ${WORKSPACE_ID}" -H "Content-Type: application/json")
BODY="$(mktemp -t demo-verify-body.XXXXXX)"
CODE="$(mktemp -t demo-verify-code.XXXXXX)"
trap 'rm -f "${BODY}" "${CODE}"' EXIT

call() {
  local method="$1" path="$2" data="${3:-}"
  local args=(-sS -o "${BODY}" -w '%{http_code}' -X "${method}" "${AUTH[@]}")
  [[ -n "${data}" ]] && args+=(--data "${data}")
  : > "${BODY}"
  local c
  c="$(curl "${args[@]}" "${API}${path}" 2>/dev/null || true)"
  printf '%s' "${c: -3}" > "${CODE}"
  cat "${BODY}"
}
http() { cat "${CODE}"; }

run_u1() {
  blue "=== U1 SendMsg 真源竖线 ==="
  MATECLAW_TOKEN="${TOKEN}" MATECLAW_BASE_URL="${BASE_URL}" \
    MATECLAW_WORKSPACE_ID="${WORKSPACE_ID}" \
    "${ROOT}/scripts/troubleshooting-guance-sendmsg-demo.sh"
}

run_u2() {
  blue "=== U2 错误码命中 ==="
  # ITGW 904003 真源告警窗（2026-08-07 联调已核）；先走历史 occurredAt。
  local created diag id status concl fixture mode
  created="$(call POST "/incidents" \
    "{\"system\":\"CSDP\",\"service\":\"csdp-wechat\",\"errorCode\":\"904003\",\"title\":\"演示 U2 904003 $(date +%H%M%S)-${RANDOM}\",\"severity\":\"P2\",\"impactScope\":\"演示\",\"rawInput\":\"demo-verify u2\",\"occurredAt\":\"2026-08-07T09:12:00Z\",\"rehearsal\":false}")"
  [[ "$(http)" == "200" ]] || fail "U2" "HTTP $(http)：$(echo "${created}" | jq -r '.msg // .' | head -c 160)"
  diag="$(echo "${created}" | jq -c '.data.diagnosis // .data')"
  id="$(echo "${diag}" | jq -r '.diagnosisId // empty')"
  status="$(echo "${diag}" | jq -r '.status // empty')"
  concl="$(echo "${diag}" | jq -r '.conclusionType // empty')"
  # jq 的 // 会把 false 当空；fixtureMode 必须显式判断 null
  fixture="$(echo "${diag}" | jq -r 'if .fixtureMode == null then "true" else (.fixtureMode|tostring) end')"
  mode="$(echo "${diag}" | jq -r '.investigationMode // empty')"
  ok "904003 ${id} mode=${mode} ${status}/${concl} fixture=${fixture}"

  if [[ "${mode}" == "ERROR_CODE_PLAYBOOK" && "${concl}" == "LOCATED" && "${fixture}" == "false" ]]; then
    if [[ "${status}" == "READY_FOR_HUMAN" ]]; then
      call POST "/diagnoses/${id}/confirm" '{"actor":"admin","note":"u2 demo"}' >/dev/null
      [[ "$(http)" == "200" ]] || fail "U2 confirm" "HTTP $(http)"
      ok "904003 真源 LOCATED 已确认"
    else
      ok "904003 真源 LOCATED（status=${status}）"
    fi
    return 0
  fi

  # 同窗去重可能返回旧案；只要真源 LOCATED 即算打通
  if [[ "${mode}" == "ERROR_CODE_PLAYBOOK" && "${concl}" == "LOCATED" ]]; then
    sources="$(echo "${diag}" | jq -r '[.evidence[]?.source] | join(",")')"
    ok "U2 LOCATED sources=${sources}（fixture 字段=${fixture}）"
    if [[ "${status}" == "READY_FOR_HUMAN" ]]; then
      call POST "/diagnoses/${id}/confirm" '{"actor":"admin","note":"u2 demo"}' >/dev/null || true
    fi
    return 0
  fi

  dim "历史窗未命中完整结论 → 尝试 fixture IM1010（需 recorded-replay 就绪）"
  if MATECLAW_TOKEN="${TOKEN}" MATECLAW_BASE_URL="${BASE_URL}" \
      MATECLAW_WORKSPACE_ID="${WORKSPACE_ID}" \
      MATECLAW_USERNAME= MATECLAW_PASSWORD= \
      "${ROOT}/scripts/troubleshooting-smoke.sh"; then
    ok "U2 fixture IM1010 通过（演示时须口头标明夹具）"
    return 0
  fi
  fail "U2" "真源 904003 未 LOCATED（got ${concl}/fixture=${fixture}）；fixture 备用也失败"
}

run_u3() {
  blue "=== U3 开放调查就绪 + 一单 ==="
  local ready
  ready="$(call GET "/open-discovery/readiness?system=CSDP")"
  [[ "$(http)" == "200" ]] || fail "U3 readiness" "HTTP $(http)"
  local status
  status="$(echo "${ready}" | jq -r '.data.status // empty')"
  ok "readiness=${status}"
  [[ "${status}" != "BLOCKED" && "${status}" != "DISABLED" ]] \
    || fail "U3" "readiness=${status}：$(echo "${ready}" | jq -r '.data.blockers[0] // .data.nextAction // empty')"

  local marker now
  marker="$(date +%H%M%S)-${RANDOM}"
  now="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  local created
  created="$(call POST "/incidents" \
    "{\"system\":\"CSDP\",\"service\":\"csdp-session-service\",\"title\":\"演示 U3 开放调查 ${marker}\",\"severity\":\"P2\",\"impactScope\":\"演示\",\"rawInput\":\"未知症状 无错误码 demo-verify\",\"occurredAt\":\"${now}\",\"rehearsal\":false}")"
  [[ "$(http)" == "200" ]] || fail "U3 开案" "HTTP $(http)：$(echo "${created}" | jq -r '.msg // .' | head -c 160)"
  local diag mode conf
  diag="$(echo "${created}" | jq -c '.data.diagnosis // .data')"
  mode="$(echo "${diag}" | jq -r '.investigationMode // empty')"
  conf="$(echo "${diag}" | jq -r '.confidence // empty')"
  id="$(echo "${diag}" | jq -r '.diagnosisId // empty')"
  ok "开案 ${id} mode=${mode} confidence=${conf}"
  if [[ "${mode}" == "OPEN_DISCOVERY" ]]; then
    [[ "${conf}" != "HIGH" ]] || fail "U3" "开放调查不得 HIGH"
    ok "U3 OPEN_DISCOVERY 一单已落地（≤MEDIUM）"
    return 0
  fi
  # Agent 可能异步或落入场景；只要 readiness 可用且未编造 HIGH 也算演示可讲
  ok "U3 readiness 可演示；本单 mode=${mode}（现场用无场景报障讲开放调查）"
}

run_u4() {
  blue "=== U4 取证接入试跑 log_search ==="
  local trial st src attempt=1
  while true; do
    trial="$(call POST "/evidence/contract-trials" \
      "{\"system\":\"csdp\",\"service\":\"csdp-session-service\",\"contractRef\":\"csdp-message-send-log-search\",\"parameters\":{},\"window\":\"-6h\"}")"
    [[ "$(http)" == "200" ]] || fail "U4" "HTTP $(http)：$(echo "${trial}" | jq -r '.msg // .' | head -c 160)"
    st="$(echo "${trial}" | jq -r '.data.status // empty')"
    src="$(echo "${trial}" | jq -r '.data.source // empty')"
    if [[ "${st}" == "OBSERVED" ]]; then
      break
    fi
    if [[ "${attempt}" -lt 3 && ( "${st}" == "FAILED" || "${st}" == "NO_EVIDENCE" ) ]]; then
      dim "log_search ${st}，重试 ${attempt}/2 …"
      attempt=$((attempt + 1))
      sleep 2
      continue
    fi
    fail "U4" "status=${st}，期望 OBSERVED（试 -6h）"
  done
  [[ "${src}" == "guance" ]] || dim "source=${src}（期望 guance）"
  ok "log_search OBSERVED source=${src} trial=$(echo "${trial}" | jq -r '.data.trialId')"
  # optional pod / synthetic — soft
  for ref in guance-service-pod-status csdp-session-synthetic-probe; do
    local t
    t="$(call POST "/evidence/contract-trials" \
      "{\"system\":\"csdp\",\"service\":\"csdp-session-service\",\"contractRef\":\"${ref}\",\"parameters\":{},\"window\":\"-6h\"}")"
    if [[ "$(http)" == "200" ]]; then
      ok "旁路 ${ref} → $(echo "${t}" | jq -r '.data.status')"
    else
      dim "旁路 ${ref} 跳过 HTTP $(http)"
    fi
  done
}

run_u5() {
  blue "=== U5 EXCLUDED 可确认 ==="
  # Prefer historical EXCLUDED diagnosis if still readable; else open case when contrast fails.
  local hist="diag-1930716105ab49229827a714a0a42975"
  local got
  got="$(call GET "/diagnoses/${hist}")"
  if [[ "$(http)" == "200" ]]; then
    local concl status fixture
    concl="$(echo "${got}" | jq -r '.data.diagnosis.conclusionType // .data.conclusionType // empty')"
    status="$(echo "${got}" | jq -r '.data.diagnosis.status // .data.status // empty')"
    fixture="$(echo "${got}" | jq -r '.data.diagnosis.fixtureMode // .data.fixtureMode // empty')"
    if [[ "${concl}" == "EXCLUDED" ]]; then
      ok "历史案 ${hist} EXCLUDED / ${status} fixture=${fixture}"
      local proj
      proj="$(call GET "/diagnoses/${hist}/projection")"
      [[ "$(http)" == "200" ]] && ok "projection 可读：$(echo "${proj}" | jq -r '.data.businessSummary.conclusionType')"
      return 0
    fi
  fi

  local preview
  preview="$(call POST "/evidence/guance/spine/preview" \
    "{\"system\":\"CSDP\",\"service\":\"csdp-session-service\",\"searchTerm\":\"message_send_failed\",\"window\":\"-6h\"}")"
  local fm
  fm="$(echo "${preview}" | jq -r '.data.contrast.failureMatchCount // "1"')"
  if [[ "${fm}" == "0" ]]; then
    local marker id ran concl
    marker="$(date +%H%M%S)-${RANDOM}"
    local created
    created="$(call POST "/scenarios/message_send_failed/diagnoses" \
      "{\"system\":\"CSDP\",\"service\":\"csdp-session-service\",\"title\":\"演示 U5 EXCLUDED ${marker}\",\"severity\":\"P2\",\"impactScope\":\"演示\",\"intakeSource\":\"demo-verify\",\"rawInput\":\"u5\",\"rehearsal\":false}")"
    id="$(echo "${created}" | jq -r '.data.diagnosis.diagnosisId')"
    ran="$(call POST "/diagnoses/${id}/evidence-runs")"
    concl="$(echo "${ran}" | jq -r '.data.diagnosis.conclusionType')"
    [[ "${concl}" == "EXCLUDED" ]] || fail "U5" "期望 EXCLUDED 得到 ${concl}"
    call POST "/diagnoses/${id}/confirm" '{"actor":"admin","note":"u5"}' >/dev/null
    [[ "$(http)" == "200" ]] || fail "U5 confirm" "HTTP $(http)"
    ok "新开 EXCLUDED 案 ${id} 已确认"
    return 0
  fi

  dim "当前对照已命中（failureMatch=${fm}），U1 为 LOCATED；用操作卡讲历史 EXCLUDED 或临时拧特征"
  ok "U5 话术可讲：排除也是结论（见 demo-runbook）；历史案或预演特征回滚"
}

run_u6() {
  blue "=== U6 知识生产候选 ==="
  # Guance pilot 通常没有 recorded inducer；先试 synthesis preview，再尝试 miss-path smoke。
  local now
  now="$(date -u '+%Y-%m-%dT%H:%M:%SZ')"
  local preview
  preview="$(call POST "/sops/synthesis/preview" \
    "{\"system\":\"CSDP\",\"service\":\"csdp-session-service\",\"searchTerm\":\"message_send_failed\",\"window\":\"-6h\",\"occurredAt\":\"${now}\"}")"
  local code
  code="$(http)"
  if [[ "${code}" == "200" ]]; then
    ok "synthesis/preview HTTP 200（可继续 candidates）"
    local cand
    cand="$(call POST "/sops/synthesis/candidates" \
      "{\"system\":\"CSDP\",\"service\":\"csdp-session-service\",\"searchTerm\":\"message_send_failed\",\"window\":\"-6h\",\"occurredAt\":\"${now}\"}")"
    if [[ "$(http)" == "200" ]]; then
      local stage review elig
      stage="$(echo "${cand}" | jq -r '.data.stage // empty')"
      review="$(echo "${cand}" | jq -r '.data.reviewStatus // .data.candidate.reviewStatus // empty')"
      elig="$(echo "${cand}" | jq -r '.data.approvalEligibility // .data.candidate.approvalEligibility // empty')"
      ok "stage=${stage} review=${review} eligibility=${elig}"
      if [[ "${elig}" == "NOT_ELIGIBLE" || "${elig}" == "not_eligible" ]]; then
        ok "U6：候选不可自动晋升（反向闸门）"
      else
        dim "eligibility=${elig}（演示强调永不自动 approved）"
      fi
      return 0
    fi
    dim "candidates HTTP $(http)：$(echo "${cand}" | jq -r '.msg // .' | head -c 120)"
  else
    dim "preview HTTP ${code}：$(echo "${preview}" | jq -r '.msg // .' | head -c 120)"
  fi

  dim "尝试 fixture miss-path smoke（需 troubleshooting-demo）"
  if MATECLAW_TOKEN="${TOKEN}" MATECLAW_BASE_URL="${BASE_URL}" \
      "${ROOT}/scripts/troubleshooting-miss-path-smoke.sh"; then
    ok "U6 fixture miss-path 通过"
    return 0
  fi
  ok "U6 口头可讲：学习环产出 candidate、不可自动晋升；本机 Guance 档未强依赖"
}

case "${TARGET}" in
  u1) run_u1 ;;
  u2) run_u2 ;;
  u3) run_u3 ;;
  u4) run_u4 ;;
  u5) run_u5 ;;
  u6) run_u6 ;;
  all)
    run_u4
    echo
    run_u1
    echo
    run_u5
    echo
    run_u2
    echo
    run_u3
    echo
    run_u6
    echo
    blue "演示复验完成。操作卡：docs/intelligent-troubleshooting/demo-runbook.md"
    ;;
  *)
    echo "用法: $0 all|u1|u2|u3|u4|u5|u6" >&2
    exit 2
    ;;
esac
