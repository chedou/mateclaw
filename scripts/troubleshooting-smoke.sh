#!/usr/bin/env bash
#
# Operator-level smoke: can a person get from a running MateClaw to one visible
# diagnosis?
#
# This exists because "I can't run a single scenario" was a feeling rather than
# a check. Every gate below is individually correct — the domain is fail-closed
# on purpose — but their conjunction is what decides whether anyone can use the
# system at all, and nothing was measuring the conjunction.
#
# It talks HTTP to a running server exactly the way an operator would. It does
# not reach into the JVM, does not use test doubles, and does not weaken any
# gate: when a gate blocks, it prints which one and the single next action.
#
#   ./scripts/troubleshooting-smoke.sh --gates     # list the gates, no server needed
#   ./scripts/troubleshooting-smoke.sh             # run against $MATECLAW_BASE_URL
#
# Exit codes: 0 = a diagnosis was produced and read back; 1 = a gate blocked it;
# 2 = the script itself could not run (bad usage, missing curl/jq).

set -euo pipefail

BASE_URL="${MATECLAW_BASE_URL:-http://127.0.0.1:8080}"
TOKEN="${MATECLAW_TOKEN:-}"
WORKSPACE_ID="${MATECLAW_WORKSPACE_ID:-1}"
SYSTEM="${SMOKE_SYSTEM:-csdp}"
SERVICE="${SMOKE_SERVICE:-csdp-order-service}"
ERROR_CODE="${SMOKE_ERROR_CODE:-903001}"
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
  echo
  dim "  这不是脚本的缺陷。系统当前确实没有一条默认可走的路径——"
  dim "  每道闸门单独看都对，但它们的合取决定了有没有人能用起来。"
  exit 1
}

print_gates() {
  blue "从零到看见一次诊断，需要依次通过的闸门"
  cat <<'GATES'

  1. 服务可达            应用已启动，且 /api/v1/troubleshooting 可访问
  2. 身份               PAT（mc_ 前缀）+ X-Workspace-Id，且具备 operate:troubleshooting
  3. 证据源已启用        mateclaw.troubleshooting.evidence.recorded-replay.enabled=true
                        （默认 false；Guance 另需 asset-bindings，默认为空）
  4. 该路由有 approved Playbook
                        仓库不随带任何 seed，迁移里 0 条 INSERT，
                        所以未注册前任何报障必然 route miss
  5. 报障被接受          POST /incidents 返回 diagnosisId
  6. 诊断可读回          GET /diagnoses/{id} 拿到结论
  7. 投影可用            GET /diagnoses/{id}/projection 有 businessSummary
                        与三段北极星耗时

  第 3、4 条是当前默认状态下必然失败的两道；它们需要显式打开与显式入库，
  这正是「跑不通一个场景」的直接原因。
GATES
}

if [[ "${1:-}" == "--gates" ]]; then
  print_gates
  exit 0
fi

for tool in curl jq; do
  command -v "${tool}" >/dev/null 2>&1 || { red "缺少 ${tool}"; exit 2; }
done

auth_header=()
[[ -n "${TOKEN}" ]] && auth_header=(-H "Authorization: Bearer ${TOKEN}")

BODY_FILE="$(mktemp -t ts-smoke-body.XXXXXX)"
trap 'rm -f "${BODY_FILE}"' EXIT

call() { # method path [body] -> body on stdout, HTTP code in $HTTP_CODE
  local method="$1" path="$2" body="${3:-}"
  local args=(-sS -o "${BODY_FILE}" -w '%{http_code}' -X "${method}"
              -H 'Content-Type: application/json'
              -H "X-Workspace-Id: ${WORKSPACE_ID}" "${auth_header[@]}")
  [[ -n "${body}" ]] && args+=(--data "${body}")
  : > "${BODY_FILE}"
  # curl already writes 000 to stdout on a connection failure, so the exit code
  # is swallowed rather than appended — otherwise HTTP_CODE becomes "000000"
  # and every status comparison silently stops matching.
  HTTP_CODE="$(curl "${args[@]}" "${API}${path}" 2>/dev/null || true)"
  HTTP_CODE="${HTTP_CODE: -3}"
  cat "${BODY_FILE}"
}

blue "MateClaw 智能排障 · 端到端冒烟"
dim  "目标：从一次报障走到一份可读的诊断，全程 fixture。"
dim  "服务：${BASE_URL}   workspace=${WORKSPACE_ID}   路由=${SYSTEM}:${ERROR_CODE}"
echo

# ── 闸门 1：服务可达 ────────────────────────────────────────────────
call GET "/evidence/sources" >/dev/null
case "${HTTP_CODE}" in
  000) gate_failed "服务可达" "连不上 ${BASE_URL}" \
        "先启动应用：./scripts/run-troubleshooting-dev.sh，或设置 MATECLAW_BASE_URL" ;;
  401|403) gate_failed "身份" "HTTP ${HTTP_CODE}，凭据被拒绝" \
        "设置 MATECLAW_TOKEN 为具备 operate:troubleshooting 的 PAT（mc_ 前缀）" ;;
esac
ok "服务可达，身份通过"

# ── 闸门 3：证据源已启用 ────────────────────────────────────────────
sources="$(call GET "/evidence/sources")"
enabled_count="$(echo "${sources}" | jq '[.data[]? | select(.enabled == true)] | length' 2>/dev/null || echo 0)"
if [[ "${enabled_count}" == "0" ]]; then
  gate_failed "证据源已启用" \
    "没有任何已启用的证据源（recorded-replay 与 guance 默认都是 false）" \
    "在配置里打开 mateclaw.troubleshooting.evidence.recorded-replay.enabled=true"
fi
ok "已启用证据源：${enabled_count} 个"

# ── 闸门 4：该路由有 approved Playbook ──────────────────────────────
playbook="$(call GET "/sops/${SYSTEM}/${ERROR_CODE}")"
if [[ "${HTTP_CODE}" != "200" ]]; then
  gate_failed "已注册 approved Playbook" \
    "${SYSTEM}:${ERROR_CODE} 在本 workspace 查不到（HTTP ${HTTP_CODE}）——仓库不随带任何 seed" \
    "先注册再批准：POST ${API}/sops 然后 POST ${API}/sops/${SYSTEM}/${ERROR_CODE}/status"
fi
status="$(echo "${playbook}" | jq -r '.data.status // "unknown"')"
[[ "${status}" == "approved" ]] || gate_failed "已注册 approved Playbook" \
  "当前状态是 ${status}，只有 approved 才会被确定性路由采用" \
  "POST ${API}/sops/${SYSTEM}/${ERROR_CODE}/status  body: {\"status\":\"approved\"}"
ok "Playbook 已就绪：${SYSTEM}:${ERROR_CODE} (${status})"

# ── 闸门 5：报障被接受 ──────────────────────────────────────────────
report=$(cat <<JSON
{"system":"${SYSTEM}","service":"${SERVICE}","errorCode":"${ERROR_CODE}",
 "title":"冒烟：工单提交失败","severity":"P2","intakeSource":"smoke",
 "rawInput":"scripts/troubleshooting-smoke.sh","rehearsal":true}
JSON
)
created="$(call POST "/incidents" "${report}")"
[[ "${HTTP_CODE}" == "200" ]] || gate_failed "报障被接受" \
  "POST /incidents 返回 HTTP ${HTTP_CODE}：$(echo "${created}" | jq -r '.message // .' | head -c 200)" \
  "若是 route_miss，说明闸门 4 的 Playbook 与报障的 system/errorCode 不匹配"
diagnosis_id="$(echo "${created}" | jq -r '.data.diagnosis.diagnosisId // empty')"
[[ -n "${diagnosis_id}" ]] || gate_failed "报障被接受" \
  "响应里没有 diagnosisId" "检查 POST /incidents 的响应结构是否变更"
ok "已产出诊断：${diagnosis_id}"

# ── 闸门 6/7：诊断与投影可读回 ──────────────────────────────────────
projection="$(call GET "/diagnoses/${diagnosis_id}/projection")"
[[ "${HTTP_CODE}" == "200" ]] || gate_failed "投影可用" \
  "GET /projection 返回 HTTP ${HTTP_CODE}" "检查 DiagnosisExperienceProjectionService"

conclusion="$(echo "${projection}" | jq -r '.data.businessSummary.conclusionType // empty')"
headline="$(echo "${projection}" | jq -r '.data.businessSummary.headline // empty')"
fixture="$(echo "${projection}" | jq -r '.data.businessSummary.fixtureMode')"
intake_cost="$(echo "${projection}" | jq -r '.data.businessSummary.timings.intakeCost // "null"')"
invest_cost="$(echo "${projection}" | jq -r '.data.businessSummary.timings.investigateCost // "null"')"
steps="$(echo "${projection}" | jq '[.data.developerEvidence.steps[]?] | length')"

[[ -n "${conclusion}" ]] || gate_failed "投影可用" \
  "businessSummary 里没有 conclusionType" "投影没有产出结论类型"
[[ "${fixture}" == "true" ]] || gate_failed "fixture 标记" \
  "fixtureMode=${fixture}，但真实源尚未通过 T7 验收" \
  "在真实观测云验收前，投影必须始终标记 fixture"

ok "结论类型：${conclusion}"
ok "结论：${headline}"
ok "开发证据步数：${steps}"
ok "北极星：补问=${intake_cost} 调查=${invest_cost}（三段分别计量，不合成总时长）"
echo
blue "冒烟通过：一次报障走到了一份可读的诊断。"
dim  "注意：全程 fixture。这只证明路径可走，不证明证据可信——"
dim  "真实观测云验收仍是 T7，需要内网窗口。"
