#!/usr/bin/env bash

# 演示：把「客户-搜索用户名超限制【1009】」告警原文粘进平台对话，直接拿到排障结论。
#
# 这条链路证明的是**确定性命中路**：解析器从括号里抽出 4 位业务码 1009，路由到
# 已生效 Playbook，取证走证据脊柱三步，判据把「失败样本全部命中用户名超限、成功
# 对照未命中」判成信号，规则输出结论。全程零 LLM。
#
# 证据来源是**录制回放**：失败计数取自告警「数量：4」，对照是按判据形状固化的夹具，
# 不是观测云拉取。因此 Diagnosis 带 fixtureMode，它不是 T7 真源已验收的证明。
#
#   ./scripts/troubleshooting-csdp-1009-demo.sh --gates   # 只看闸门
#   ./scripts/troubleshooting-csdp-1009-demo.sh           # 跑完整演示
#
# 前置：服务已启动，且回放源已开启（MATECLAW_TROUBLESHOOTING_REPLAY_ENABLED=true）。
# 观测云必须保持关闭：csdp-wechat 的 log_search 目前绑定的是 ITGW 904003 合同，
# 真源打开会把 904003 的计数当成 1009 证据喂进来。

set -euo pipefail

BASE_URL="${MATECLAW_BASE_URL:-http://127.0.0.1:18088}"
PUBLIC_BASE_URL="${MATECLAW_PUBLIC_BASE_URL:-http://127.0.0.1:5173}"
TOKEN="${MATECLAW_TOKEN:-}"
USERNAME="${MATECLAW_USERNAME:-}"
PASSWORD="${MATECLAW_PASSWORD:-}"
WORKSPACE_ID="${MATECLAW_WORKSPACE_ID:-1}"
API="${BASE_URL}/api/v1/troubleshooting"

SELECTOR="csdp:1009"
SOP_ID="manual-csdp-search-username-limit-1009-v1"
REASON="演示：1009 搜索用户名超限制按录制回放证明晋升"

bold() { printf '\033[1m%s\033[0m\n' "$*"; }
dim()  { printf '\033[2m%s\033[0m\n' "$*"; }
red()  { printf '\033[31m%s\033[0m\n' "$*"; }
green(){ printf '\033[32m%s\033[0m\n' "$*"; }

print_gates() {
  cat <<'TXT'
闸门（任一不过即失败，不得跳过）

  1. 合同可晋升      服务端能按 selector 给出候选合同，且回放证明 PASSED
  2. 批准走审核      approve 必须经 start → approve，不能直接改 status
  3. 一发命中        只粘告警原文一条消息，Intake 必须直接 READY
  4. 四位码路由      命中必须来自解析出的错误码 1009，不是无码场景
  5. 结论有判据      两个信号都必须成立：超限出现、且对照区分失败/成功
  6. 如实标注        Diagnosis 必须标 fixtureMode，不得冒充真源已验收
TXT
}

if [[ "${1:-}" == "--gates" ]]; then
  print_gates
  exit 0
fi

for tool in curl jq; do
  command -v "${tool}" >/dev/null 2>&1 || { red "缺少 ${tool}"; exit 2; }
done

gate_failed() { # gate reason next-step
  red "✗ 闸门未通过：$1"
  echo "  现象：$2"
  echo "  下一步：$3"
  exit 1
}

if [[ -z "${TOKEN}" && -n "${USERNAME}" ]]; then
  TOKEN="$(curl -sS -X POST "${BASE_URL}/api/v1/auth/login" \
      -H 'Content-Type: application/json' \
      --data "{\"username\":\"${USERNAME}\",\"password\":\"${PASSWORD}\"}" \
      2>/dev/null | jq -r '.data.token // empty')"
  [[ -n "${TOKEN}" ]] || gate_failed "合同可晋升" \
    "用 ${USERNAME} 登录 ${BASE_URL} 未拿到 token" \
    "确认服务已启动且账号密码正确，或用 MATECLAW_TOKEN 提供 PAT"
fi

auth_header=()
[[ -n "${TOKEN}" ]] && auth_header=(-H "Authorization: Bearer ${TOKEN}")

BODY_FILE="$(mktemp -t ts-1009-body.XXXXXX)"
CODE_FILE="$(mktemp -t ts-1009-code.XXXXXX)"
trap 'rm -f "${BODY_FILE}" "${CODE_FILE}"' EXIT

call() { # method path [body] -> body on stdout; status via http_code
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

bold "MateClaw · 搜索用户名超限制 1009 告警端到端演示"
dim  "服务：${BASE_URL}   workspace=${WORKSPACE_ID}   路由=${SELECTOR}"
echo

# ── 闸门 1/2：让 1009 成为权威 ─────────────────────────────────────
bold "① 让「1009 搜索用户名超限制」成为已生效 Playbook"

existing="$(call GET "/sops/CSDP/1009")"
if [[ "$(http_code)" == "200" ]] \
   && [[ "$(echo "${existing}" | jq -r '.data.status // empty')" == "approved" ]]; then
  green "  已生效，跳过晋升"
else
  candidate="$(call GET "/sops/review-inbox/manual/example?selectorKey=${SELECTOR}")"
  [[ "$(http_code)" == "200" ]] || gate_failed "合同可晋升" \
    "取候选合同返回 HTTP $(http_code)：$(echo "${candidate}" | jq -r '.msg // .' | head -c 200)" \
    "确认 manual-playbook-replay-suites.json 里的 ${SELECTOR} 种子没有被隔离（看启动日志 quarantined）"

  registered="$(call POST "/sops" "$(echo "${candidate}" | jq -c '.data')")"
  [[ "$(http_code)" == "200" ]] || gate_failed "合同可晋升" \
    "注册候选返回 HTTP $(http_code)：$(echo "${registered}" | jq -r '.msg // .' | head -c 200)" \
    "检查 SopManagementController.register 与候选合同字段"
  dim  "  候选已注册：${SOP_ID}"

  attest="$(call POST "/sops/review-inbox/manual/${SOP_ID}/replay")"
  status="$(echo "${attest}" | jq -r '.data.status // empty')"
  [[ "${status}" == "PASSED" ]] || gate_failed "合同可晋升" \
    "回放证明 status=${status:-HTTP $(http_code)}，failureCodes=$(echo "${attest}" | jq -c '.data.failureCodes // empty')" \
    "对照 recorded-replay-catalog.json 里的三条 csdp-wechat / 1009 记录与种子 positiveCase"
  suite_id="$(echo "${attest}" | jq -r '.data.suiteId // "?"')"
  green "  回放证明 PASSED（suite=${suite_id}，正例 $(echo "${attest}" | jq -r '.data.positivePassed')/$(echo "${attest}" | jq -r '.data.positiveTotal')，反例 $(echo "${attest}" | jq -r '.data.negativeOrAbstainPassed')/$(echo "${attest}" | jq -r '.data.negativeOrAbstainTotal')）"

  review="$(call POST "/sops/review-inbox/MANUAL/${SOP_ID}/start" \
      "{\"expectedVersion\":0,\"reason\":\"${REASON}\"}")"
  version="$(echo "${review}" | jq -r '.data.version // empty')"
  [[ -n "${version}" ]] || gate_failed "批准走审核" \
    "start 返回 HTTP $(http_code)：$(echo "${review}" | jq -r '.msg // .' | head -c 200)" \
    "检查 KnowledgeReviewWorkflowService.start"

  approved="$(call POST "/sops/review-inbox/MANUAL/${SOP_ID}/approve" \
      "{\"expectedVersion\":${version},\"reason\":\"${REASON}\"}")"
  [[ "$(http_code)" == "200" ]] || gate_failed "批准走审核" \
    "approve 返回 HTTP $(http_code)：$(echo "${approved}" | jq -r '.msg // .' | head -c 300)" \
    "资格不足时先看 review-inbox 的 sourceStates.eligibility"
  green "  已批准为 Playbook 版本 $(echo "${approved}" | jq -r '.data.playbookVersion // .data.version // "?"')"
fi
echo

# ── 闸门 3–6：粘告警原文，直接出结论 ────────────────────────────────
bold "② 把告警原文粘进平台对话"

ALERT=$'客服数字化(WECHAT)-【客户-搜索用户名超限制】-事件\n■【紧急】2026-08-14 13:06:00 (r/93bf1d)\n集群：sz3-s-k8s\n服务：csdp-wechat\n数量：4\n异常：客户-搜索用户名超限制【1009】\n说明：异常事件'
dim "$(echo "${ALERT}" | sed 's/^/  │ /')"
echo

turn="$(call POST "/conversation/turns" \
    "$(jq -n --arg t "${ALERT}" --arg c "demo-1009-$(date +%s)" \
        '{conversationId:$c, text:$t, rehearsal:false}')")"
[[ "$(http_code)" == "200" ]] || gate_failed "一发命中" \
  "对话轮次返回 HTTP $(http_code)：$(echo "${turn}" | jq -r '.msg // .' | head -c 400)" \
  "409 通常说明解析器没抽出 1009，或还没有已生效的 csdp:1009 Playbook"

status="$(echo "${turn}" | jq -r '.data.status // empty')"
missing="$(echo "${turn}" | jq -c '.data.missingFields // []')"
[[ "${status}" == "READY" ]] || gate_failed "一发命中" \
  "Intake 状态是 ${status}，仍缺 ${missing}" \
  "system 靠已生效 Playbook 按 service+1009 唯一反查；customerRef 靠「集群」行判定为监控告警"

diagnosis_id="$(echo "${turn}" | jq -r '.data.diagnosisId // empty')"
[[ -n "${diagnosis_id}" ]] || gate_failed "结论有判据" \
  "READY 了但没有 diagnosisId" "检查 ConversationIntakeService 的同步 report 分支"
green "  Intake 一轮 READY，Diagnosis=${diagnosis_id}"
echo

bold "③ 排障结论"
echo "${turn}" | jq -r '.data.prompt // empty' | sed 's/^/  /'
echo

# ── 结论溯源核验 ───────────────────────────────────────────────────
detail="$(call GET "/diagnoses/${diagnosis_id}")"
d() { echo "${detail}" | jq -r ".data.diagnosis.$1 // empty"; }
sop_key="$(d sopKey)"
route_mode="$(d routeMode)"
fixture="$(d fixtureMode)"
signals="$(echo "${detail}" | jq -r '[.data.diagnosis.triggeredSignals[]?] | sort | join(",")')"
pb="$(d 'sourcePlaybookVersionRef.playbookId')/v$(d 'sourcePlaybookVersionRef.playbookVersion')"

[[ "${sop_key}" == "csdp:1009" ]] || gate_failed "四位码路由" \
  "命中的是 '${sop_key:-空}'，不是 csdp:1009" \
  "核对解析器是否抽出 1009，以及已生效 Playbook 的 selector"
[[ "${route_mode}" == "DETERMINISTIC" ]] || gate_failed "结论有判据" \
  "routeMode=${route_mode:-空}，命中路必须零 LLM" \
  "确认走的是 approved Playbook 而不是未命中路 Agent"
[[ "${signals}" == "username_limit_discriminated,username_search_limit_present" ]] || gate_failed "结论有判据" \
  "触发信号是 [${signals:-空}]，两个判据必须都成立" \
  "确认对照证据满足 failure_success_rate_contrast（4/4 vs 0/4）"
[[ "${fixture}" == "true" ]] || gate_failed "如实标注" \
  "fixtureMode=${fixture:-空}，回放来源必须标 fixture" \
  "证据源可能落到了观测云；关闭 MATECLAW_TROUBLESHOOTING_GUANCE_ENABLED 再跑"

green "✓ 六道闸门全过"
dim   "  路由 ${sop_key}（${route_mode}）· Playbook ${pb} · fixtureMode=${fixture}"
dim   "  触发信号 ${signals}"
dim   "  工作台：${PUBLIC_BASE_URL}/troubleshooting?diagnosisId=${diagnosis_id}"
echo
dim   "边界：失败计数来自告警「数量：4」，对照是回放夹具，不是观测云真源。"
dim   "真源需要 D20 场景维度授权 + T7 owner 验收后才能切换。"
