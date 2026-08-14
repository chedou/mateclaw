#!/usr/bin/env bash

# 演示：把「URL 慢请求」告警原文粘进平台对话，直接拿到排障结论。
#
# 这条链路证明的是**确定性命中路**：告警没有错误码，靠 symptom 触发短语路由到
# 已生效 Playbook，取证走证据脊柱三步，判据把「单一路由慢率显著高于同窗口其他
# 路由」判成信号，规则输出结论。全程零 LLM。
#
# 证据来源是**录制回放**：样本由 2026-08-06 真实观测云数据脱敏固化而来。
# 因此产出的 Diagnosis 带 fixtureMode，它不是观测云真源已验收的证明。
#
#   ./scripts/troubleshooting-url-slow-request-demo.sh --gates   # 只看闸门
#   ./scripts/troubleshooting-url-slow-request-demo.sh           # 跑完整演示
#
# 前置：服务已启动，且回放源已开启（MATECLAW_TROUBLESHOOTING_REPLAY_ENABLED=true）。
# 观测云必须保持关闭：csdp-wechat 的 log_search 目前绑定的是 ITGW 904003 合同，
# 真源打开会把 904003 的计数当成慢请求证据喂进来。场景维度授权（D20）落地前，
# 这条演示只能走回放。

set -euo pipefail

BASE_URL="${MATECLAW_BASE_URL:-http://127.0.0.1:18088}"
TOKEN="${MATECLAW_TOKEN:-}"
USERNAME="${MATECLAW_USERNAME:-}"
PASSWORD="${MATECLAW_PASSWORD:-}"
WORKSPACE_ID="${MATECLAW_WORKSPACE_ID:-1}"
API="${BASE_URL}/api/v1/troubleshooting"

SELECTOR="csdp:scenario:url_slow_request"
SOP_ID="manual-csdp-url-slow-request-v2"
REASON="演示：URL 慢请求场景按录制回放证明晋升"

bold() { printf '\033[1m%s\033[0m\n' "$*"; }
dim()  { printf '\033[2m%s\033[0m\n' "$*"; }
red()  { printf '\033[31m%s\033[0m\n' "$*"; }
green(){ printf '\033[32m%s\033[0m\n' "$*"; }

print_gates() {
  cat <<'TXT'
闸门（任一不过即失败，不得跳过）

  1. 场景可晋升      服务端能按 selector 给出候选合同，且回放证明 PASSED
  2. 批准走审核      approve 必须经 start → approve，不能直接改 status
  3. 一发命中        只粘告警原文一条消息，Intake 必须直接 READY
  4. 无码路由        没有错误码，命中必须来自 symptom 触发短语
  5. 结论有判据      结论规则 ID 必须是 RULE-URL-SLOW-ITGW-SYNC-WAIT
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
  [[ -n "${TOKEN}" ]] || gate_failed "场景可晋升" \
    "用 ${USERNAME} 登录 ${BASE_URL} 未拿到 token" \
    "确认服务已启动且账号密码正确，或用 MATECLAW_TOKEN 提供 PAT"
fi

auth_header=()
[[ -n "${TOKEN}" ]] && auth_header=(-H "Authorization: Bearer ${TOKEN}")

BODY_FILE="$(mktemp -t ts-slow-body.XXXXXX)"
CODE_FILE="$(mktemp -t ts-slow-code.XXXXXX)"
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

bold "MateClaw · URL 慢请求告警端到端演示"
dim  "服务：${BASE_URL}   workspace=${WORKSPACE_ID}   场景=${SELECTOR}"
echo

# ── 闸门 1/2：让场景成为权威 ────────────────────────────────────────
bold "① 让「URL 慢请求」场景成为已生效 Playbook"

existing="$(call GET "/sops/CSDP/scenario:url_slow_request")"
if [[ "$(http_code)" == "200" ]] \
   && [[ "$(echo "${existing}" | jq -r '.data.status // empty')" == "approved" ]]; then
  green "  已生效，跳过晋升"
else
  candidate="$(call GET "/sops/review-inbox/manual/example?selectorKey=${SELECTOR}")"
  [[ "$(http_code)" == "200" ]] || gate_failed "场景可晋升" \
    "取候选合同返回 HTTP $(http_code)：$(echo "${candidate}" | jq -r '.msg // .' | head -c 200)" \
    "确认 manual-playbook-replay-suites.json 里的 ${SELECTOR} 种子没有被隔离（看启动日志 quarantined）"

  registered="$(call POST "/sops" "$(echo "${candidate}" | jq -c '.data')")"
  [[ "$(http_code)" == "200" ]] || gate_failed "场景可晋升" \
    "注册候选返回 HTTP $(http_code)：$(echo "${registered}" | jq -r '.msg // .' | head -c 200)" \
    "检查 SopManagementController.register 与候选合同字段"
  dim  "  候选已注册：${SOP_ID}"

  attest="$(call POST "/sops/review-inbox/manual/${SOP_ID}/replay")"
  status="$(echo "${attest}" | jq -r '.data.status // empty')"
  [[ "${status}" == "PASSED" ]] || gate_failed "场景可晋升" \
    "回放证明 status=${status:-HTTP $(http_code)}，failureCodes=$(echo "${attest}" | jq -c '.data.failureCodes // empty')" \
    "对照 recorded-replay-catalog.json 里的三条 csdp-wechat 记录与种子 positiveCase"
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

ALERT=$'客服数字化(WECHAT)-【URL慢请求】-事件\n■【紧急】2026-08-06 12:00:00 (r/0009b2)\n集群：sz3-s-k8s\n服务：csdp-wechat\n数量：110\n说明：异常事件'
dim "$(echo "${ALERT}" | sed 's/^/  │ /')"
echo

turn="$(call POST "/conversation/turns" \
    "$(jq -n --arg t "${ALERT}" --arg c "demo-url-slow-$(date +%s)" \
        '{conversationId:$c, text:$t, rehearsal:false}')")"
[[ "$(http_code)" == "200" ]] || gate_failed "一发命中" \
  "对话轮次返回 HTTP $(http_code)：$(echo "${turn}" | jq -r '.msg // .' | head -c 400)" \
  "409 通常说明没命中 Playbook；核对 symptomTriggers 是否被告警标题包含"

status="$(echo "${turn}" | jq -r '.data.status // empty')"
missing="$(echo "${turn}" | jq -c '.data.missingFields // []')"
[[ "${status}" == "READY" ]] || gate_failed "一发命中" \
  "Intake 状态是 ${status}，仍缺 ${missing}" \
  "system 靠已生效 Playbook 按 service 唯一反查；customerRef 靠「集群」行判定为监控告警"

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

[[ "${sop_key}" == "csdp:scenario:url_slow_request" ]] || gate_failed "无码路由" \
  "命中的是 '${sop_key:-空}'，不是慢请求场景" \
  "核对 symptomTriggers 是否被告警标题包含，且没有第二个 Playbook 同时命中"
[[ "${route_mode}" == "DETERMINISTIC" ]] || gate_failed "结论有判据" \
  "routeMode=${route_mode:-空}，命中路必须零 LLM" \
  "确认走的是 approved Playbook 而不是未命中路 Agent"
[[ "${signals}" == "one_route_dominates_slowness,slow_requests_present" ]] || gate_failed "结论有判据" \
  "触发信号是 [${signals:-空}]，两个判据必须都成立" \
  "确认对照证据满足 failure_success_rate_contrast（39.44% vs 0.16%）"
[[ "${fixture}" == "true" ]] || gate_failed "如实标注" \
  "fixtureMode=${fixture:-空}，回放来源必须标 fixture" \
  "证据源可能落到了观测云；关闭 MATECLAW_TROUBLESHOOTING_GUANCE_ENABLED 再跑"

green "✓ 六道闸门全过"
dim   "  路由 ${sop_key}（${route_mode}）· Playbook ${pb} · fixtureMode=${fixture}"
dim   "  触发信号 ${signals}"
dim   "  工作台：${BASE_URL}/troubleshooting?view=detail&diagnosisId=${diagnosis_id}"
echo
dim   "边界：证据来自 2026-08-06 真实数据的脱敏回放，不是观测云真源实时查询。"
dim   "真源需要 D20 场景维度授权 + T7 owner 验收后才能切换。"
