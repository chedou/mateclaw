#!/usr/bin/env bash
#
# The no-error-code lane, end to end: 报障（无错误码）→ 选场景 → 跑取证 →
# 结论可确认。
#
# Why this exists next to the other three. troubleshooting-smoke.sh walks the
# error-code hit path; troubleshooting-scenario-smoke.sh walks one case from
# report to closure — but both start from an errorCode. The lane that starts
# from a symptom had a hole in the middle: naming a scenario created a Diagnosis
# that abstained and waited, and nothing ran its evidence plan. Deployment
# topology had its own probe endpoint, so it was the only scenario that could
# finish. Every other one stopped at NEEDS_INVESTIGATION forever.
#
# The gate that carries this script is 2: confirm MUST be refused before the
# evidence runs. Without it, gates 3 and 4 would pass on a system that had
# simply never been stuck, and the fix would be unfalsifiable.
#
#   ./scripts/troubleshooting-scenario-evidence-smoke.sh --gates
#   ./scripts/troubleshooting-scenario-evidence-smoke.sh
#
# Exit codes: 0 = the symptom lane ran to a confirmable conclusion;
# 1 = a gate blocked it; 2 = the script itself could not run.

set -euo pipefail

BASE_URL="${MATECLAW_BASE_URL:-http://127.0.0.1:18088}"
TOKEN="${MATECLAW_TOKEN:-}"
USERNAME="${MATECLAW_USERNAME:-}"
PASSWORD="${MATECLAW_PASSWORD:-}"
WORKSPACE_ID="${MATECLAW_WORKSPACE_ID:-1}"
SYSTEM="${SCENARIO_SYSTEM:-CSDP}"
SERVICE="${SCENARIO_SERVICE:-csdp-session-service}"
SCENARIO_KEY="${SCENARIO_KEY:-message_send_failed}"
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
  dim "  无错误码的报障是蓝图 §11.1 首个验收场景的形状。"
  dim "  这条 lane 断在中间，等于「在线上拿不到结论」。"
  exit 1
}

print_gates() {
  blue "一条无错误码的报障走到「结论可确认」，需要依次通过的闸门"
  cat <<'GATES'

  1. 选场景即可开案         POST /scenarios/{key}/diagnoses 产出 Diagnosis，
                          且必须是 INSUFFICIENT_EVIDENCE / NEEDS_INVESTIGATION。
                          选场景是选取证计划，不是断言原因
  2. **取证前不得确认**      此时 confirm 必须 409。
                          没有这一格，第 3、4 格就是在一个从未卡住的系统上通过的，
                          修复也就无从证伪
  3. 跑取证计划            POST /diagnoses/{id}/evidence-runs 真正执行 Playbook
                          自己的 evidenceRequests，并让判据与规则重新求值
  4. 取证后可确认           结论推进到 READY_FOR_HUMAN 且 abstained=false，
                          confirm 返回 200
  5. 结论出自 Playbook      rootCause 必须等于该 Playbook 写下的那一条，
                          不是取证环节现编的
  6. 引用恰好等于取到的证据   evidenceCitations 必须与非 MISSING 的取证一一对应。
                          多列 = 拿「我们查过」当依据；空清单 = 这道闸门在空转（A1）
  7. 重跑被拒绝            人已看过结论之后再 POST evidence-runs 必须 409，
                          而不是悄悄改写一个别人可能已经据此行动的结论
  8. 多场景且结局不同        同一条 lane 至少跑通三个场景，且**必须包含一个 EXCLUDED**。
                          只会产出 LOCATED 的 demo 会夸大——「排除」也是结论，
                          而且是更常见的那种：多数排查是一段段划掉可能性

  第 2 格是这条脚本存在的理由。
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
  [[ -n "${TOKEN}" ]] || gate_failed "选场景即可开案" \
    "用 ${USERNAME} 登录 ${BASE_URL} 未拿到 token" \
    "确认服务已启动且账号密码正确，或改用 MATECLAW_TOKEN 提供 PAT"
fi

auth_header=()
[[ -n "${TOKEN}" ]] && auth_header=(-H "Authorization: Bearer ${TOKEN}")

BODY_FILE="$(mktemp -t ts-scenario-evidence-body.XXXXXX)"
CODE_FILE="$(mktemp -t ts-scenario-evidence-code.XXXXXX)"
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

blue "MateClaw 智能排障 · 无错误码 lane（报现象 → 选场景 → 跑取证 → 可确认）"
dim  "服务：${BASE_URL}   workspace=${WORKSPACE_ID}   场景=${SYSTEM}:scenario:${SCENARIO_KEY}"
echo

# ── 闸门 1：选场景即可开案 ──────────────────────────────────────────
# The title carries a run marker on purpose. Scenario intake deduplicates on
# (system, service, scenario, normalized title, 5-minute bucket), so a fixed
# title would hand back the previous run's already-confirmed Diagnosis and this
# script could only ever pass once. That dedup is correct product behaviour and
# has its own tests; here it just means each run must open a genuinely new case.
RUN_MARKER="${RUN_MARKER:-$(date -u +%H%M%S)-${RANDOM}}"
report=$(cat <<JSON
{"system":"${SYSTEM}","service":"${SERVICE}",
 "title":"会话消息发送失败（冒烟 ${RUN_MARKER}）","severity":"P1","impactScope":"部分会话",
 "intakeSource":"scenario-evidence-smoke",
 "rawInput":"scripts/troubleshooting-scenario-evidence-smoke.sh","rehearsal":false}
JSON
)
created="$(call POST "/scenarios/${SCENARIO_KEY}/diagnoses" "${report}")"
[[ "$(http_code)" == "200" ]] || gate_failed "选场景即可开案" \
  "POST /scenarios/${SCENARIO_KEY}/diagnoses 返回 HTTP $(http_code)：$(echo "${created}" | jq -r '.msg // .' | head -c 200)" \
  "确认 demo 档已 seed 了 ${SYSTEM,,}:scenario:${SCENARIO_KEY} 的已审核 Playbook"
diagnosis_id="$(echo "${created}" | jq -r '.data.diagnosis.diagnosisId // empty')"
[[ -n "${diagnosis_id}" ]] || gate_failed "选场景即可开案" "响应里没有 diagnosisId" \
  "检查 ScenarioDiagnosisController 的响应结构"
conclusion="$(echo "${created}" | jq -r '.data.diagnosis.conclusionType')"
status="$(echo "${created}" | jq -r '.data.diagnosis.status')"
[[ "${conclusion}" == "INSUFFICIENT_EVIDENCE" && "${status}" == "NEEDS_INVESTIGATION" ]] \
  || gate_failed "选场景即可开案" \
     "开案即为 ${conclusion}/${status}，期望 INSUFFICIENT_EVIDENCE/NEEDS_INVESTIGATION" \
     "选场景只选取证计划；如果选个场景就能拿到结论，路由/权威的区分就是装饰"
ok "已开案：${diagnosis_id}（${conclusion} / ${status}）"

# ── 闸门 2：取证前不得确认 ──────────────────────────────────────────
early="$(call POST "/diagnoses/${diagnosis_id}/confirm")"
[[ "$(http_code)" == "409" ]] || gate_failed "取证前不得确认" \
  "取证前 confirm 返回 HTTP $(http_code)，期望 409" \
  "弃权的诊断必须先有新证据才能确认；否则后面两格是在一个从未卡住的系统上通过的"
ok "取证前确认被拒（409）：$(echo "${early}" | jq -r '.msg' | head -c 70)"

# ── 闸门 3：跑取证计划 ──────────────────────────────────────────────
ran="$(call POST "/diagnoses/${diagnosis_id}/evidence-runs")"
[[ "$(http_code)" == "200" ]] || gate_failed "跑取证计划" \
  "POST /diagnoses/${diagnosis_id}/evidence-runs 返回 HTTP $(http_code)：$(echo "${ran}" | jq -r '.msg // .' | head -c 200)" \
  "检查 ScenarioEvidenceRunService；若报「asset tool」，说明该 Playbook 的必需证据由资产工具负责"
collected="$(echo "${ran}" | jq '[.data.diagnosis.evidence[]?] | length')"
[[ "${collected}" -gt 0 ]] || gate_failed "跑取证计划" "取证后 evidence 为空" \
  "取证计划必须真的跑过 Playbook 自己的 evidenceRequests"
ok "取证已执行：${collected} 条证据"

# ── 闸门 4：取证后可确认 ────────────────────────────────────────────
after_status="$(echo "${ran}" | jq -r '.data.diagnosis.status')"
abstained="$(echo "${ran}" | jq -r '.data.diagnosis.abstained')"
[[ "${after_status}" == "READY_FOR_HUMAN" && "${abstained}" == "false" ]] \
  || gate_failed "取证后可确认" \
     "取证后仍是 ${after_status}（abstained=${abstained}）" \
     "若证据确实没取到，这是对的；但夹具回放下应当命中。检查 recorded replay 绑定"
confirmed="$(call POST "/diagnoses/${diagnosis_id}/confirm")"
[[ "$(http_code)" == "200" ]] || gate_failed "取证后可确认" \
  "取证后 confirm 仍返回 HTTP $(http_code)：$(echo "${confirmed}" | jq -r '.msg' | head -c 160)" \
  "证据到达必须真正清掉 abstained，否则这条 lane 依旧走不完"
ok "取证后已确认：${after_status} → $(echo "${confirmed}" | jq -r '.data.diagnosis.status')"

# ── 闸门 5：结论出自 Playbook ───────────────────────────────────────
root_cause="$(echo "${ran}" | jq -r '.data.diagnosis.rootCause // empty')"
playbook_root="$(call GET "/sops/${SYSTEM,,}/scenario:${SCENARIO_KEY}" \
  | jq -r '[.data.diagnosisRules[]?.rootCause] | first // empty')"
if [[ -n "${playbook_root}" ]]; then
  [[ "${root_cause}" == "${playbook_root}" ]] || gate_failed "结论出自 Playbook" \
    "诊断给出的 rootCause 是「${root_cause}」，Playbook 写的是「${playbook_root}」" \
    "取证环节不得生成 Playbook 没写过的根因"
  ok "结论出自 Playbook：${root_cause}"
else
  gate_failed "结论出自 Playbook" \
    "读不到 ${SYSTEM,,}:scenario:${SCENARIO_KEY} 的 diagnosisRules，无法比对" \
    "确认 GET /sops/{system}/{errorCode} 可读；读不到就无法证明结论不是现编的"
fi

# ── 闸门 6：引用恰好等于取到的证据 ──────────────────────────────────
# Both directions, deliberately. A one-sided "no MISSING is cited" check passes
# on an empty list — and an empty list is exactly what a typo'd field name
# returns. The field is evidenceCitations; querying citedEvidence made this
# gate vacuous until it was caught against a live response.
answered="$(echo "${ran}" | jq -c '
  [.data.diagnosis.evidence[]? | select(.status != "MISSING") | .queryId] | sort')"
cited="$(echo "${ran}" | jq -c '[.data.diagnosis.evidenceCitations[]?] | sort')"
[[ "${cited}" != "[]" ]] || gate_failed "引用恰好等于取到的证据" \
  "evidenceCitations 为空——要么响应字段名变了，要么结论没有任何证据支撑" \
  "空清单会让这道闸门变成空转；先确认响应里的字段名，再确认结论确有依据"
[[ "${cited}" == "${answered}" ]] || gate_failed "引用恰好等于取到的证据" \
  "引用 ${cited} 与真正取到的 ${answered} 不一致" \
  "多列了 MISSING 就是拿「我们查过」当依据；少列了就是结论没说清它凭什么（A1）"
ok "引用恰好等于取到的证据：$(echo "${cited}" | jq -r 'join(", ")')"

# ── 闸门 7：重跑被拒绝 ──────────────────────────────────────────────
rerun="$(call POST "/diagnoses/${diagnosis_id}/evidence-runs")"
[[ "$(http_code)" == "409" ]] || gate_failed "重跑被拒绝" \
  "人已确认之后重跑取证返回 HTTP $(http_code)，期望 409" \
  "重跑不得悄悄改写一个别人可能已经据此行动的结论；那是一次新调查"
ok "重跑被拒（409）：$(echo "${rerun}" | jq -r '.msg' | head -c 80)"

# ── 闸门 8：多场景且结局不同 ────────────────────────────────────────
# 这一格挡的是「lane 被某一个场景特化」和「demo 只会给好看的结论」两件事。
# 排除（EXCLUDED）是这里唯一必须出现的结局：它此前只在单测里断言过，
# 从没在 HTTP 边界上走出来过。
declare -a EXPECTED=(
  "message_send_failed:csdp-session-service:LOCATED"
  "gateway_timeout:csdp-api-gateway:EXCLUDED"
  "auth_token_rejected:csdp-auth:LOCATED"
)
seen_excluded=0
for spec in "${EXPECTED[@]}"; do
  IFS=: read -r key service want <<<"${spec}"
  body="$(call POST "/scenarios/${key}/diagnoses" \
    "{\"system\":\"${SYSTEM}\",\"service\":\"${service}\",
      \"title\":\"多场景冒烟 ${key} ${RUN_MARKER}\",\"severity\":\"P1\",
      \"impactScope\":\"部分\",\"intakeSource\":\"scenario-evidence-smoke\",
      \"rawInput\":\"multi\",\"rehearsal\":false}")"
  [[ "$(http_code)" == "200" ]] || gate_failed "多场景且结局不同" \
    "开案 ${key} 返回 HTTP $(http_code)：$(echo "${body}" | jq -r '.msg // .' | head -c 160)" \
    "确认 demo 档已 seed 该场景的已审核 Playbook"
  id="$(echo "${body}" | jq -r '.data.diagnosis.diagnosisId // empty')"
  ran="$(call POST "/diagnoses/${id}/evidence-runs")"
  [[ "$(http_code)" == "200" ]] || gate_failed "多场景且结局不同" \
    "${key} 跑取证返回 HTTP $(http_code)：$(echo "${ran}" | jq -r '.msg // .' | head -c 160)" \
    "该场景的录制证据可能缺失或与 Playbook 的 evidenceRequests 对不上"
  got="$(echo "${ran}" | jq -r '.data.diagnosis.conclusionType')"
  [[ "${got}" == "${want}" ]] || gate_failed "多场景且结局不同" \
    "${key} 得到 ${got}，期望 ${want}" \
    "结局变了要么是判据/规则被改，要么是录制证据被改；两者都该有人明确决定"
  [[ "${got}" == "EXCLUDED" ]] && seen_excluded=1
  ok "${key} → ${got}"
done
[[ "${seen_excluded}" == "1" ]] || gate_failed "多场景且结局不同" \
  "三个场景全部给出了非 EXCLUDED 的结论" \
  "至少要有一个场景走到「排除」；只会产出 LOCATED 的 demo 会夸大能力"
ok "多场景通过：三个场景、两种结局，含一个真正的「排除」"

echo
blue "无错误码 lane 通过：从一句现象走到了可确认的结论。"
dim  "最要紧的一格是闸门 2：取证之前 confirm 必须被拒。"
dim  "没有那一格，后面的通过就证明不了任何东西。"
dim  "注意：全程 recorded replay 夹具，A10——回放通过不等于真实观测云已验证。"
