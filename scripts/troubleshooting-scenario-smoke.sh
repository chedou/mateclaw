#!/usr/bin/env bash
#
# Operator-level smoke for ONE COMPLETE CASE: report → diagnosis → confirm →
# transfer → authorize a production write → record what a human did outside the
# platform → close → sediment a reviewable knowledge candidate.
#
# Why this exists on top of troubleshooting-smoke.sh. That script stops at
# "the diagnosis is readable", which is roughly the first third of what a
# 服务经理 and a 处置人 actually do. Everything after it — hand-off, approval,
# external outcome, recovery verification, closure — is where the north star's
# 「可交接」 lives, and where the single most important red line lives:
#
#     人工批准只推进状态机，不触发执行。
#
# That red line was only ever demonstrated in its refusing half: POST /execute
# answers 409. The affirmative half — approve moves the action to
# APPROVED_NOT_EXECUTED, nothing runs, execution stays BLOCKED, and a human
# reports back through record-outcome — had never been walked over HTTP,
# because no seeded Playbook carried a MANUAL_WRITE action at all.
#
# csdp:903001 now carries one. It is the hand-authored fixture, so its job is
# no longer to be a second piece of knowledge competing with the real-data
# IM1010 — it is the instrument that exercises this boundary.
#
#   ./scripts/troubleshooting-scenario-smoke.sh --gates
#   ./scripts/troubleshooting-scenario-smoke.sh
#
# Exit codes: 0 = one case ran to closure; 1 = a gate blocked it; 2 = the
# script itself could not run.

set -euo pipefail

BASE_URL="${MATECLAW_BASE_URL:-http://127.0.0.1:18088}"
TOKEN="${MATECLAW_TOKEN:-}"
USERNAME="${MATECLAW_USERNAME:-}"
PASSWORD="${MATECLAW_PASSWORD:-}"
WORKSPACE_ID="${MATECLAW_WORKSPACE_ID:-1}"
# The fixture Playbook, chosen because it is the one carrying a MANUAL_WRITE
# action. Its numbers are authored, which is exactly why it is used as an
# instrument here rather than as knowledge.
SYSTEM="${SCENARIO_SYSTEM:-csdp}"
SERVICE="${SCENARIO_SERVICE:-order-svc}"
ERROR_CODE="${SCENARIO_ERROR_CODE:-903001}"
WRITE_ACTION="${SCENARIO_WRITE_ACTION:-A2}"
TARGET_TEAM="${SCENARIO_TARGET_TEAM:-数据库平台组}"
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
  dim "  一个案子只走到「诊断可读」就停，等于只演示了前三分之一。"
  dim "  交接、批准、外部登记、恢复验证、关闭，才是人真正做的那部分。"
  exit 1
}

print_gates() {
  blue "一个案子从报障走到关闭，需要依次通过的闸门"
  cat <<'GATES'

  1. 产出诊断            报障被接受，拿到 diagnosisId 与结论
  2. 有生产写动作         该 Playbook 必须带一个 MANUAL_WRITE 动作。
                        没有它，这条产品最核心的红线就只有「拒绝」那一半被演示过
  3. 确认                READY_FOR_HUMAN → CONFIRMED
  4. 交接                CONFIRMED → TRANSFERRED，且带着能免去重新诊断的 note
  5. 未批准不得登记外部结果 record-outcome 在批准前必须 409，且 409 的原因必须是
                        「尚未批准」——挡在别的前置上等于没测到它
  6. **批准不等于执行**    approve 后 approvalStatus=APPROVED_NOT_EXECUTED，
                        而 executionStatus 必须仍然是 BLOCKED。
                        这是整条链里唯一一道「必须什么都没发生」的闸门
  7. 执行仍被拒绝         批准之后 POST /execute 依旧 409。
                        授权不会解锁执行器，因为根本没有执行器
  8. 登记外部结果         人在平台外做完，回填 outcome 与 recoveryVerified
  9. 关闭并沉淀知识       CLOSED，且产出一条 OUTCOME_BACKED 候选（带 ownerTeam）
 10. 候选不可被自动晋升    新沉淀的知识同样是 NOT_ELIGIBLE，
                        关闭一个案子不构成知识审核

  第 6 条是这条脚本存在的理由。
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
  [[ -n "${TOKEN}" ]] || gate_failed "产出诊断" \
    "用 ${USERNAME} 登录 ${BASE_URL} 未拿到 token" \
    "确认服务已启动且账号密码正确，或改用 MATECLAW_TOKEN 提供 PAT"
fi

auth_header=()
[[ -n "${TOKEN}" ]] && auth_header=(-H "Authorization: Bearer ${TOKEN}")

BODY_FILE="$(mktemp -t ts-scenario-body.XXXXXX)"
CODE_FILE="$(mktemp -t ts-scenario-code.XXXXXX)"
trap 'rm -f "${BODY_FILE}" "${CODE_FILE}"' EXIT

# Status travels through a file because callers use `body="$(call ...)"`, which
# runs this in a subshell; a plain variable would silently keep an earlier
# request's code.
call() { # method path [body] -> body on stdout; read status with http_code
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

action_field() { # diagnosis-json actionId field
  echo "$1" | jq -r --arg a "$2" --arg f "$3" \
    '[.data.diagnosis.recommendedActions[]?, .data.diagnosis.pendingWrites[]?]
     | map(select(.actionId == $a)) | first | .[$f] // empty'
}

blue "MateClaw 智能排障 · 单案端到端场景（报障 → 关闭）"
dim  "目标：把一个案子真正走完，而不是停在「诊断可读」。"
dim  "服务：${BASE_URL}   workspace=${WORKSPACE_ID}   路由=${SYSTEM}:${ERROR_CODE}"
echo

# ── 闸门 1：产出诊断 ────────────────────────────────────────────────
report=$(cat <<JSON
{"system":"${SYSTEM}","service":"${SERVICE}","errorCode":"${ERROR_CODE}",
 "title":"场景：工单提交失败","severity":"P1","intakeSource":"scenario-smoke",
 "rawInput":"scripts/troubleshooting-scenario-smoke.sh","rehearsal":false}
JSON
)
created="$(call POST "/incidents" "${report}")"
[[ "$(http_code)" == "200" ]] || gate_failed "产出诊断" \
  "POST /incidents 返回 HTTP $(http_code)：$(echo "${created}" | jq -r '.msg // .' | head -c 200)" \
  "先跑 ./scripts/troubleshooting-smoke.sh 确认命中路可用"
diagnosis_id="$(echo "${created}" | jq -r '.data.diagnosis.diagnosisId // empty')"
[[ -n "${diagnosis_id}" ]] || gate_failed "产出诊断" "响应里没有 diagnosisId" \
  "检查 POST /incidents 的响应结构是否变更"
ok "诊断已产出：${diagnosis_id}（$(echo "${created}" | jq -r '.data.diagnosis.conclusionType')）"

# ── 闸门 2：有生产写动作 ────────────────────────────────────────────
detail="$(call GET "/diagnoses/${diagnosis_id}")"
write_type="$(action_field "${detail}" "${WRITE_ACTION}" actionType)"
[[ "${write_type}" == "MANUAL_WRITE" ]] || gate_failed "有生产写动作" \
  "动作 ${WRITE_ACTION} 的类型是 ${write_type:-不存在}，不是 MANUAL_WRITE" \
  "这条 Playbook 没有生产写动作，红线只有「拒绝」那一半能被演示；
         给夹具 Playbook 加一个 MANUAL_WRITE 动作再跑"
initial_exec="$(action_field "${detail}" "${WRITE_ACTION}" executionStatus)"
[[ "${initial_exec}" == "BLOCKED" ]] || gate_failed "有生产写动作" \
  "生产写动作初始 executionStatus=${initial_exec}，期望 BLOCKED" \
  "MANUAL_WRITE 从注册那一刻起就必须是 BLOCKED"
ok "生产写动作就位：${WRITE_ACTION}（BLOCKED）"

# ── 闸门 3/4：确认与交接 ────────────────────────────────────────────
confirmed="$(call POST "/diagnoses/${diagnosis_id}/confirm")"
[[ "$(echo "${confirmed}" | jq -r '.data.diagnosis.status')" == "CONFIRMED" ]] \
  || gate_failed "确认" "状态是 $(echo "${confirmed}" | jq -r '.data.diagnosis.status')" \
     "确认只推进状态机，不执行任何东西"
ok "已确认：READY_FOR_HUMAN → CONFIRMED"

transferred="$(call POST "/diagnoses/${diagnosis_id}/transfer" \
  "{\"targetTeam\":\"${TARGET_TEAM}\",\"note\":\"证据已定位到连接池被慢查询占满；请核查长事务来源\"}")"
[[ "$(echo "${transferred}" | jq -r '.data.diagnosis.status')" == "TRANSFERRED" ]] \
  || gate_failed "交接" "状态是 $(echo "${transferred}" | jq -r '.data.diagnosis.status')" \
     "交接要带上下文快照，接手的人才不用重新诊断一遍"
ok "已交接：${TARGET_TEAM}"

# ── 闸门 5：未批准不得登记外部结果 ──────────────────────────────────
# Deliberately placed after confirm. Before it, record-outcome fails on the
# confirmation precondition instead, and the gate would silently be testing
# something other than what it claims.
early="$(call POST "/diagnoses/${diagnosis_id}/actions/${WRITE_ACTION}/record-outcome" \
  '{"outcome":"SUCCEEDED","notes":"未经批准就想回填","recoveryVerified":true}')"
[[ "$(http_code)" == "409" ]] || gate_failed "未批准不得登记外部结果" \
  "批准前 record-outcome 返回 HTTP $(http_code)，期望 409" \
  "没有这道拒绝，任何人都能凭空宣称一次生产变更已经做过"
early_msg="$(echo "${early}" | jq -r '.msg')"
[[ "${early_msg}" == *approved* ]] || gate_failed "未批准不得登记外部结果" \
  "409 的原因是「${early_msg}」，不是「尚未批准」" \
  "这道闸门必须挡在批准前置上；挡在别的前置上等于没测到它"
ok "批准前登记被拒（409）：${early_msg}"

# ── 闸门 6：批准不等于执行 ──────────────────────────────────────────
approved="$(call POST "/diagnoses/${diagnosis_id}/actions/${WRITE_ACTION}/approve" \
  '{"reason":"连接池已满且慢查询确认为长事务，授权 DBA 在平台外终止会话"}')"
[[ "$(http_code)" == "200" ]] || gate_failed "批准不等于执行" \
  "approve 返回 HTTP $(http_code)：$(echo "${approved}" | jq -r '.msg' | head -c 160)" \
  "检查 DiagnosisLifecycleService.approveAction"
approval_status="$(action_field "${approved}" "${WRITE_ACTION}" approvalStatus)"
execution_status="$(action_field "${approved}" "${WRITE_ACTION}" executionStatus)"
[[ "${approval_status}" == "APPROVED_NOT_EXECUTED" ]] || gate_failed "批准不等于执行" \
  "approvalStatus=${approval_status}，期望 APPROVED_NOT_EXECUTED" \
  "批准只表示授权，不表示做过"
[[ "${execution_status}" == "BLOCKED" ]] || gate_failed "批准不等于执行" \
  "executionStatus=${execution_status}，期望仍然是 BLOCKED —— 批准把执行解锁了" \
  "这是整个产品安全论证的支点：平台没有生产写执行器，批准不得改变这一点"
ok "批准生效但什么都没发生：approval=${approval_status}，execution=${execution_status}"

# ── 闸门 7：执行仍被拒绝 ────────────────────────────────────────────
refused="$(call POST "/diagnoses/${diagnosis_id}/actions/${WRITE_ACTION}/execute")"
[[ "$(http_code)" == "409" ]] || gate_failed "执行仍被拒绝" \
  "批准之后 execute 返回 HTTP $(http_code)，期望 409" \
  "授权绝不能解锁执行器"
ok "批准之后执行依旧被拒（409）：$(echo "${refused}" | jq -r '.msg' | head -c 70)"

# ── 闸门 8：登记外部结果 ────────────────────────────────────────────
recorded="$(call POST "/diagnoses/${diagnosis_id}/actions/${WRITE_ACTION}/record-outcome" \
  '{"outcome":"SUCCEEDED","notes":"DBA 在平台外终止了两个长事务会话，连接池水位回落","recoveryVerified":true}')"
[[ "$(http_code)" == "200" ]] || gate_failed "登记外部结果" \
  "record-outcome 返回 HTTP $(http_code)：$(echo "${recorded}" | jq -r '.msg' | head -c 160)" \
  "外部处置完成后必须能回填，否则闭环断在人这一侧"
outcome_count="$(echo "${recorded}" | jq '[.data.diagnosis.actionOutcomes[]?] | length')"
[[ "${outcome_count}" -gt 0 ]] || gate_failed "登记外部结果" "actionOutcomes 为空" \
  "回填结果必须落台账"
ok "外部结果已登记：${outcome_count} 条，recoveryVerified=true"

# ── 闸门 9：关闭并沉淀知识 ──────────────────────────────────────────
closed="$(call POST "/diagnoses/${diagnosis_id}/close" \
  '{"outcome":"RECOVERED","summary":"慢查询占满连接池，DBA 终止长事务后恢复","recoveryVerified":true,"sopFeedback":"判据有效","createKnowledgeCandidate":true}')"
[[ "$(echo "${closed}" | jq -r '.data.diagnosis.status')" == "CLOSED" ]] \
  || gate_failed "关闭并沉淀知识" \
     "状态是 $(echo "${closed}" | jq -r '.data.diagnosis.status')" "检查关闭前置条件"
candidate_id="$(echo "${closed}" | jq -r '.data.diagnosis.knowledgeCandidates[0].candidateId // empty')"
owner_team="$(echo "${closed}" | jq -r '.data.diagnosis.knowledgeCandidates[0].ownerTeam // empty')"
[[ -n "${candidate_id}" ]] || gate_failed "关闭并沉淀知识" \
  "关闭后没有产出知识候选" "createKnowledgeCandidate=true 时必须沉淀一条候选"
ok "已关闭并沉淀候选：${candidate_id}（owner=${owner_team}）"

# ── 闸门 10：候选不可被自动晋升 ─────────────────────────────────────
inbox="$(call GET "/sops/review-inbox")"
# The lane is sourceStates, not sources. Querying the wrong key returns empty
# and an `if [[ -n ... ]]` around it would turn this gate into a no-op.
eligibility="$(echo "${inbox}" | jq -r --arg c "${candidate_id}" \
  '[.data.sourceStates[]? | select(.sourceRecordId == $c) | .snapshot.approvalEligibility] | first // empty')"
[[ -n "${eligibility}" ]] || gate_failed "候选不可被自动晋升" \
  "刚沉淀的候选 ${candidate_id} 在评审台账里找不到" \
  "关闭产出的候选必须出现在 review-inbox 的 sourceStates 里，否则没有人会去审它"
[[ "${eligibility}" == "NOT_ELIGIBLE" ]] || gate_failed "候选不可被自动晋升" \
  "新沉淀的候选 approvalEligibility=${eligibility}" \
  "关闭一个案子不构成知识审核；晋升必须另走评审与回放"
blockers="$(echo "${inbox}" | jq -r --arg c "${candidate_id}" \
  '[.data.sourceStates[]? | select(.sourceRecordId == $c) | .snapshot.eligibilityReasons[]?] | join(", ")')"
ok "新知识候选：${eligibility}（阻塞原因：${blockers}）"

echo
blue "场景通过：一个案子从报障走到了关闭。"
dim  "最要紧的一格是闸门 6：批准之后 executionStatus 仍然是 BLOCKED——"
dim  "平台没有生产写执行器，授权只推进状态机，变更由人在平台之外完成。"
dim  "注意：全程 fixture，${SYSTEM}:${ERROR_CODE} 的阈值是手写的，它在这里是夹具不是知识。"
