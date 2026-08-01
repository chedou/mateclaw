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

BASE_URL="${MATECLAW_BASE_URL:-http://127.0.0.1:18088}"
TOKEN="${MATECLAW_TOKEN:-}"
USERNAME="${MATECLAW_USERNAME:-}"
PASSWORD="${MATECLAW_PASSWORD:-}"
WORKSPACE_ID="${MATECLAW_WORKSPACE_ID:-1}"
DIAGNOSIS_OBSERVED_AT_FILE="${MATECLAW_SMOKE_DIAGNOSIS_OBSERVED_AT_FILE:-}"
SYSTEM="${SMOKE_SYSTEM:-csdp}"
# Must match the seeded Playbook and the recorded fixture: the replay adapter
# looks records up by (system, errorCode, service, requestId), so a plausible
# but wrong service name yields MISSING evidence and a silent 证据不足.
SERVICE="${SMOKE_SERVICE:-csp-rpc-msg}"
ERROR_CODE="${SMOKE_ERROR_CODE:-IM1010}"
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
  2. 身份               PAT（mc_ 前缀）或 MATECLAW_USERNAME/PASSWORD 登录换取的 JWT，
                        加 X-Workspace-Id，且具备 operate:troubleshooting
  3. 证据源已启用        mateclaw.troubleshooting.evidence.recorded-replay.enabled=true
                        （默认 false；Guance 另需 asset-bindings，默认为空）
  4. 该路由有 approved Playbook
                        默认 profile 不写入 seed；troubleshooting-demo 会把服务端候选逐条
                        走完固定回放证明与知识审核，再生成 approved 版本。
                        注意"批准"不是改个状态位：它必须先通过服务端固定回放套件，
                        再走知识评审晋升出一个新版本（updateStatus 对 approved 是 fail-closed 的）
  5. 报障被接受          POST /incidents 返回 diagnosisId
  6. 诊断可读回          GET /diagnoses/{id} 拿到结论
  7. 投影可用            GET /diagnoses/{id}/projection 有 businessSummary
                        与三段北极星耗时
  8. 判据真的被求值过      结论不是 INSUFFICIENT_EVIDENCE。取证断掉时前七道全绿，
                        因为"证据不足"同时也是系统在真实缺证据时的正确输出

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

# Either a scoped PAT (mc_ prefix) or a username/password the platform's own
# login endpoint exchanges for a JWT. The same filter accepts both, so the
# smoke path never needs a credential shape that operators do not already have.
if [[ -z "${TOKEN}" && -n "${USERNAME}" ]]; then
  TOKEN="$(curl -sS -X POST "${BASE_URL}/api/v1/auth/login" \
      -H 'Content-Type: application/json' \
      --data "{\"username\":\"${USERNAME}\",\"password\":\"${PASSWORD}\"}" \
      2>/dev/null | jq -r '.data.token // empty')"
  [[ -n "${TOKEN}" ]] || gate_failed "身份" \
    "用 ${USERNAME} 登录 ${BASE_URL} 未拿到 token" \
    "确认服务已启动且账号密码正确，或改用 MATECLAW_TOKEN 提供 PAT"
fi

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
# The contract is EvidenceSourceHealth{platform,status,verified,detail}; a
# disabled adapter reports DISABLED rather than being absent. DEGRADED is not
# counted: a source that cannot answer is not a path.
ready="$(echo "${sources}" | jq -r '[.data[]? | select(.status == "READY") | .platform] | join(", ")' 2>/dev/null || echo "")"
if [[ -z "${ready}" ]]; then
  gate_failed "证据源已启用" \
    "没有任何 READY 的证据源：$(echo "${sources}" | jq -c '[.data[]? | {platform,status}]' 2>/dev/null)" \
    "在配置里打开 mateclaw.troubleshooting.evidence.recorded-replay.enabled=true"
fi
ok "READY 的证据源：${ready}"

# ── 闸门 4：该路由有 approved Playbook ──────────────────────────────
playbook="$(call GET "/sops/${SYSTEM}/${ERROR_CODE}")"
if [[ "${HTTP_CODE}" != "200" ]]; then
  gate_failed "已注册 approved Playbook" \
    "${SYSTEM}:${ERROR_CODE} 在本 workspace 查不到（HTTP ${HTTP_CODE}）" \
    "启用 troubleshooting-demo，或让候选先通过 replay 再走 knowledge review 晋升"
fi
status="$(echo "${playbook}" | jq -r '.data.status // "unknown"')"
[[ "${status}" == "approved" ]] || gate_failed "已注册 approved Playbook" \
  "当前状态是 ${status}，只有 approved 才会被确定性路由采用" \
  "运行服务端 replay 并走 knowledge review；兼容 status 接口不会绕过晋升闸门"
ok "Playbook 已就绪：${SYSTEM}:${ERROR_CODE} (${status})"

# ── 闸门 5：报障被接受 ──────────────────────────────────────────────
report=$(cat <<JSON
{"system":"${SYSTEM}","service":"${SERVICE}","errorCode":"${ERROR_CODE}",
 "title":"冒烟：消息发送失败","severity":"P0","intakeSource":"smoke",
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
if [[ -n "${DIAGNOSIS_OBSERVED_AT_FILE}" ]]; then
  printf '%s\n' "$(date +%s)" > "${DIAGNOSIS_OBSERVED_AT_FILE}" || {
    red "无法记录首条 Diagnosis 的观测时间"
    exit 2
  }
fi
ok "已产出诊断：${diagnosis_id}"

# ── 闸门 6/7：诊断与投影可读回 ──────────────────────────────────────
projection="$(call GET "/diagnoses/${diagnosis_id}/projection")"
[[ "${HTTP_CODE}" == "200" ]] || gate_failed "投影可用" \
  "GET /projection 返回 HTTP ${HTTP_CODE}" "检查 DiagnosisExperienceProjectionService"

conclusion="$(echo "${projection}" | jq -r '.data.businessSummary.conclusionType // empty')"
headline="$(echo "${projection}" | jq -r '.data.businessSummary.headline // empty')"
fixture="$(echo "${projection}" | jq -r '.data.businessSummary.fixtureMode')"
reported_at="$(echo "${projection}" | jq -r '.data.businessSummary.timings.reportedAt // empty')"
ready_at="$(echo "${projection}" | jq -r '.data.businessSummary.timings.readyAt // empty')"
conclusion_at="$(echo "${projection}" | jq -r '.data.businessSummary.timings.conclusionAt // empty')"
handoff_at="$(echo "${projection}" | jq -r '.data.businessSummary.timings.handoffAt // "null"')"
intake_cost="$(echo "${projection}" | jq -r '.data.businessSummary.timings.intakeCost // "null"')"
invest_cost="$(echo "${projection}" | jq -r '.data.businessSummary.timings.investigateCost // "null"')"
adopt_cost="$(echo "${projection}" | jq -r '.data.businessSummary.timings.adoptCost // "null"')"
steps="$(echo "${projection}" | jq '[.data.developerEvidence.steps[]?] | length')"

[[ -n "${conclusion}" ]] || gate_failed "投影可用" \
  "businessSummary 里没有 conclusionType" "投影没有产出结论类型"

# ── 闸门 8：判据真的被求值过 ────────────────────────────────────────
# 没有这道闸门，一次全程 UNEVALUATED 的诊断也会被判为"通过"：接入没问题、
# 投影没问题、结论字段也在——只是链路在取证那一步就断了，而"证据不足"正是
# 系统在真实缺证据时的正确输出，两者从外面看一模一样。
# 对这条种子场景，回放样本是齐的，所以 INSUFFICIENT_EVIDENCE 只可能意味着配错。
if [[ "${conclusion}" == "INSUFFICIENT_EVIDENCE" ]]; then
  unevaluated="$(echo "${projection}" \
    | jq -r '[.data.developerEvidence.steps[]? | select(.tone == "UNEVALUATED") | .ref] | join(", ")')"
  gate_failed "判据真的被求值过" \
    "结论是 INSUFFICIENT_EVIDENCE，未求值项：${unevaluated}" \
    "回放样本按 (system, errorCode, service, requestId) 精确匹配；
         先核对报障的 SMOKE_SERVICE=${SERVICE} 是否与种子 Playbook 和样本里的 service 一致"
fi

[[ "${fixture}" == "true" ]] || gate_failed "fixture 标记" \
  "fixtureMode=${fixture}，但真实源尚未通过 T7 验收" \
  "在真实观测云验收前，投影必须始终标记 fixture"

[[ "${steps}" =~ ^[0-9]+$ && "${steps}" -gt 0 ]] || gate_failed "开发证据" \
  "developerEvidence.steps 没有任何可读步骤" \
  "检查 DiagnosisExperienceProjectionService 的开发者投影"

[[ -n "${reported_at}" && -n "${ready_at}" && -n "${conclusion_at}" \
   && "${intake_cost}" != "null" && "${invest_cost}" != "null" ]] \
  || gate_failed "北极星前两段耗时" \
    "reportedAt=${reported_at:-null} readyAt=${ready_at:-null} conclusionAt=${conclusion_at:-null} \
intakeCost=${intake_cost} investigateCost=${invest_cost}" \
    "检查 Intake 与 Diagnosis 是否在真实边界记录 NorthStarTimings"

[[ "${handoff_at}" == "null" && "${adopt_cost}" == "null" ]] \
  || gate_failed "北极星第三段耗时" \
    "尚未人工确认的冒烟诊断应保持 handoffAt/adoptCost 为 null，实际为 \
handoffAt=${handoff_at} adoptCost=${adopt_cost}" \
    "检查是否在未发生人工采纳时伪造了第三段耗时"

ok "结论类型：${conclusion}"
ok "结论：${headline}"
ok "开发证据步数：${steps}"
ok "北极星：补问=${intake_cost} 调查=${invest_cost} 采纳=未发生（三段分别计量）"
echo
blue "冒烟通过：一次报障走到了一份可读的诊断。"
dim  "注意：全程 fixture。这只证明路径可走，不证明证据可信——"
dim  "真实观测云验收仍是 T7，需要内网窗口。"
