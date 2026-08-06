#!/usr/bin/env bash
#
# Operator-level smoke for the MISS path: can a person get from a no-error-code
# report to one reviewable piece of knowledge?
#
# Why this exists separately from troubleshooting-smoke.sh. That script proves
# the hit path — a known error code routes to an approved Playbook and produces
# a diagnosis with zero LLM calls. It is the path that *consumes* knowledge.
#
# The blueprint's only named "must pass first" acceptance case (§11.1) is the
# opposite one: a report with no error code, investigated through
# log_search → PS ID → log_trace_bundle, ending in a reviewable PlaybookDraft
# compared against the human solution. That is the path that *produces*
# knowledge — and it was never default-runnable, for exactly the reason the hit
# path was not before P1.5: its gates are individually right and their
# conjunction leaves no walkable route.
#
# The two scripts share the gate narrative deliberately, and share no gate.
#
#   ./scripts/troubleshooting-miss-path-smoke.sh --gates   # list gates, no server
#   ./scripts/troubleshooting-miss-path-smoke.sh           # run against $MATECLAW_BASE_URL
#
# Exit codes: 0 = a reviewable candidate was produced and read back;
# 1 = a gate blocked it; 2 = the script itself could not run.

set -euo pipefail

BASE_URL="${MATECLAW_BASE_URL:-http://127.0.0.1:18088}"
TOKEN="${MATECLAW_TOKEN:-}"
USERNAME="${MATECLAW_USERNAME:-}"
PASSWORD="${MATECLAW_PASSWORD:-}"
WORKSPACE_ID="${MATECLAW_WORKSPACE_ID:-1}"
# Must match the recorded replay fixture's no-code records, which are looked up
# by (system, service, searchTerm). The meeting case is the authority here.
SYSTEM="${SMOKE_SYSTEM:-CSDP}"
SERVICE="${SMOKE_SERVICE:-csdp-session-service}"
SEARCH_TERM="${SMOKE_SEARCH_TERM:-message_send_failed}"
SCENARIO_KEY="${SMOKE_SCENARIO_KEY:-message_send_failed}"
WINDOW="${SMOKE_WINDOW:--15m}"
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
  dim "  这不是脚本的缺陷。学习环——也就是新知识的来源——目前没有一条默认可走的路径。"
  dim "  在线排障闭环消费知识，知识生产闭环供给知识；供给侧还没通电。"
  exit 1
}

print_gates() {
  blue "从一条无错误码报障，到一条可评审的知识，需要依次通过的闸门"
  cat <<'GATES'

  1. 服务可达 + 身份      同命中路：PAT（mc_ 前缀）或 USERNAME/PASSWORD 换 JWT，
                        且具备 admin（synthesis 端点要求 admin，不是 member）
  2. 三次取证可完成       log_search → log_trace_bundle → contrast_sample 全部路由到已启用的源；
                        无码路没有 Playbook 兜底，取证断掉就没有下一步
  3. 确定性压缩有产出     preview 返回有界调用链骨架，且 PS ID 一致
  4. 模型可用            无码路必须调一次模型。demo 用服务端录制响应，
                        provider 标记为 recorded，绝不冒用真实 provider 名
  5. 草稿通过确定性校验   引用、selector、动作、DQL/raw log、secret 全部合规
  6. 候选被创建          CANDIDATE_CREATED，且 reviewStatus=CANDIDATE
  7. 候选**不可**被自动晋升 approvalEligibility 必须是 NOT_ELIGIBLE。
                        这一道是反向断言：产出知识很容易，产出"不会被误当权威的知识"才难
  8. 与人工解法有结构化差异 referenceComparison 存在且非空，不是一个空壳对象
  9. 幂等                同 generationKey 重跑得到 CANDIDATE_REUSED，不产生第二条候选
 10. 在线 lane 能接住      同一个无码故障能走场景入口落一份合法诊断。
                        注意它只到"接住"为止：诊断停在 NEEDS_INVESTIGATION 等待取证，
                        场景侧的证据执行件尚未实现（拓扑场景有专用探针端点，无码场景没有）
 11. 没有编造            该诊断 errorCode 必须为 null、结论必须是 INSUFFICIENT_EVIDENCE、
                        权威必须是 EXPLICIT——指定场景是选证据计划，不是断言原因

  第 2、4 条是当前默认状态下必然失败的两道：demo profile 完全没有覆盖模型侧。
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
  [[ -n "${TOKEN}" ]] || gate_failed "服务可达 + 身份" \
    "用 ${USERNAME} 登录 ${BASE_URL} 未拿到 token" \
    "确认服务已启动且账号密码正确，或改用 MATECLAW_TOKEN 提供 PAT"
fi

auth_header=()
[[ -n "${TOKEN}" ]] && auth_header=(-H "Authorization: Bearer ${TOKEN}")

BODY_FILE="$(mktemp -t ts-miss-smoke.XXXXXX)"
CODE_FILE="$(mktemp -t ts-miss-code.XXXXXX)"
trap 'rm -f "${BODY_FILE}" "${CODE_FILE}"' EXIT

# Callers use `body="$(call ...)"`, which runs the function in a subshell, so a
# plain HTTP_CODE assignment never reaches the caller. Route the status through
# a file, which does survive the subshell. (The hit-path script had the same
# defect: every status check silently read an earlier request's code.)
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

blue "MateClaw 智能排障 · 无码路端到端冒烟（学习环）"
dim  "目标：从一条无错误码报障，走到一条可评审、且不可被自动晋升的知识。"
dim  "服务：${BASE_URL}   workspace=${WORKSPACE_ID}   案例=${SYSTEM}/${SERVICE}/${SEARCH_TERM}"
echo

OCCURRED_AT="$(date -u -d '-5 minutes' '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null \
  || date -u -v-5M '+%Y-%m-%dT%H:%M:%SZ')"
REPORTED_AT="$(date -u -d '-3 minutes' '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null \
  || date -u -v-3M '+%Y-%m-%dT%H:%M:%SZ')"
READY_AT="$(date -u -d '-2 minutes' '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null \
  || date -u -v-2M '+%Y-%m-%dT%H:%M:%SZ')"
# Stable across reruns on purpose: gate 9 asserts generationKey idempotence,
# which a fresh incident id every run would silently never exercise.
INCIDENT_ID="${SMOKE_INCIDENT_ID:-incident-miss-path-smoke}"

# ── 闸门 1：服务可达 + 身份 ─────────────────────────────────────────
preview_body=$(cat <<JSON
{"system":"${SYSTEM}","service":"${SERVICE}","searchTerm":"${SEARCH_TERM}",
 "window":"${WINDOW}","occurredAt":"${OCCURRED_AT}"}
JSON
)
preview="$(call POST "/sops/synthesis/preview" "${preview_body}")"
case "$(http_code)" in
  000) gate_failed "服务可达 + 身份" "连不上 ${BASE_URL}" \
        "先 test-compile 并运行 package-troubleshooting-demo-fixture.sh，再把专用 fixture Jar 加入 additional-classpath-elements" ;;
  401) gate_failed "服务可达 + 身份" "HTTP 401，凭据被拒绝" \
        "设置 MATECLAW_TOKEN 或 MATECLAW_USERNAME/PASSWORD" ;;
  403) gate_failed "服务可达 + 身份" "HTTP 403：synthesis 端点要求 admin，不是 member" \
        "用具备 workspace admin 的身份重试" ;;
esac
ok "服务可达，身份通过（admin）"

# ── 闸门 2/3：三次取证 + 确定性压缩 ─────────────────────────────────
[[ "$(http_code)" == "200" ]] || gate_failed "三次取证可完成" \
  "POST /sops/synthesis/preview 返回 HTTP $(http_code)：$(echo "${preview}" | jq -r '.msg // .message // .' | head -c 200)" \
  "确认 recorded-replay 已启用，且 CSDP 的 log_search / log_trace_bundle / contrast_sample 都已路由"

ps_id="$(echo "${preview}" | jq -r '.data.skeleton.psId // .data.psId // empty')"
hop_count="$(echo "${preview}" | jq '[.data.skeleton.serviceSequence[]?] | length' 2>/dev/null || echo 0)"
contrast_available="$(echo "${preview}" | jq -r '.data.contrastAvailable // false')"
[[ -n "${ps_id}" ]] || gate_failed "确定性压缩有产出" \
  "preview 里没有 PS ID：$(echo "${preview}" | jq -c '.data | keys' 2>/dev/null)" \
  "无码路的整条链靠 PS ID 串起来；拿不到它，后面的骨架就不是同一次故障"
[[ "${hop_count}" -gt 0 ]] || gate_failed "确定性压缩有产出" \
  "调用链骨架为空（serviceSequence=0）" \
  "检查 DeterministicLogTraceCompressor 与回放样本是否对齐"
ok "三次取证完成，PS ID=${ps_id}，调用链服务数=${hop_count}，成功样本对照=${contrast_available}"

# ── 闸门 4/5/6：模型 → 校验 → 候选 ──────────────────────────────────
generate_body=$(cat <<JSON
{"system":"${SYSTEM}","service":"${SERVICE}","searchTerm":"${SEARCH_TERM}",
 "window":"${WINDOW}","occurredAt":"${OCCURRED_AT}",
 "sourceIncidentId":"${INCIDENT_ID}",
 "reportedAt":"${REPORTED_AT}","readyAt":"${READY_AT}"}
JSON
)
generated="$(call POST "/sops/synthesis/candidates" "${generate_body}")"
[[ "$(http_code)" == "200" ]] || gate_failed "候选被创建" \
  "POST /sops/synthesis/candidates 返回 HTTP $(http_code)：$(echo "${generated}" | jq -r '.msg // .message // .' | head -c 200)" \
  "检查 synthesis 生成端点是否可用"

stage="$(echo "${generated}" | jq -r '.data.stage // empty')"
errors="$(echo "${generated}" | jq -c '.data.errors // []')"
case "${stage}" in
  CANDIDATE_CREATED|CANDIDATE_REUSED) ;;
  ABSTAINED) gate_failed "候选被创建" \
      "归纳弃权：$(echo "${generated}" | jq -r '.data.rejectedDraft.abstainReason // "未给出原因"')" \
      "弃权本身是合法输出，但 demo 的录制证据应当足以支撑一条草稿；核对录制响应与回放样本是否漂移" ;;
  MODEL_REJECTED) gate_failed "模型可用" \
      "模型不可用或响应不合法：${errors}" \
      "demo 应当使用专用 fixture Jar 的录制响应（provider=recorded）；确认 fixture Jar 与 troubleshooting-demo profile 均已启用" ;;
  VALIDATION_REJECTED) gate_failed "草稿通过确定性校验" \
      "确定性校验拒绝了草稿：${errors}" \
      "这是校验在做它该做的事。核对录制响应是否包含 DQL、原始日志、生产写动作或伪造引用" ;;
  *) gate_failed "候选被创建" "未知 stage=${stage}" "检查 PlaybookSynthesisResult.Stage 是否新增了取值" ;;
esac
ok "归纳完成：stage=${stage}"

provider="$(echo "${generated}" | jq -r '.data.candidate.draft.modelProvenance.provider // "unknown"')"
review_status="$(echo "${generated}" | jq -r '.data.candidate.reviewStatus // empty')"
[[ "${review_status}" == "CANDIDATE" ]] || gate_failed "候选被创建" \
  "reviewStatus=${review_status}，期望 CANDIDATE" \
  "新产出的知识只能是 candidate；出现别的值说明有人绕过了评审台账"
ok "候选已写入：reviewStatus=${review_status}，归纳来源 provider=${provider}"

# ── 闸门 7：候选不可被自动晋升（反向断言）───────────────────────────
eligibility="$(echo "${generated}" | jq -r '.data.candidate.approvalEligibility // empty')"
[[ "${eligibility}" == "NOT_ELIGIBLE" ]] || gate_failed "候选不可被自动晋升" \
  "approvalEligibility=${eligibility}，期望 NOT_ELIGIBLE" \
  "产出知识很容易，产出\"不会被误当权威的知识\"才难。证据型草稿必须先补齐
         owner / 正例回放 / 负例回放，才谈得上晋升资格"
ok "晋升资格：${eligibility}（这一道是反向断言——它必须失败才算通过）"

fixture="$(echo "${generated}" | jq -r '.data.candidate.fixtureMode')"
[[ "${fixture}" == "true" ]] || gate_failed "fixture 标记" \
  "fixtureMode=${fixture}，但真实源尚未通过 T7 验收" \
  "真实观测云验收前，产出必须始终标记 fixture"

# ── 闸门 8：与人工解法有结构化差异 ──────────────────────────────────
comparison="$(echo "${generated}" | jq -c '.data.candidate.referenceComparison // null')"
[[ "${comparison}" != "null" ]] || gate_failed "与人工解法有结构化差异" \
  "referenceComparison 缺失" \
  "会议验收要求逐项对照人工解法；缺了它，这条草稿就没有被任何东西检验过"
compared_passed="$(echo "${generated}" | jq -r '.data.candidate.referenceComparison.passed')"
ok "与人工解法对照：passed=${compared_passed}"

# ── 闸门 9：幂等 ────────────────────────────────────────────────────
repeat="$(call POST "/sops/synthesis/candidates" "${generate_body}")"
repeat_stage="$(echo "${repeat}" | jq -r '.data.stage // empty')"
first_id="$(echo "${generated}" | jq -r '.data.candidate.recordId // empty')"
repeat_id="$(echo "${repeat}" | jq -r '.data.candidate.recordId // empty')"
[[ "${repeat_stage}" == "CANDIDATE_REUSED" || "${repeat_stage}" == "CANDIDATE_CREATED" ]] \
  || gate_failed "幂等" "重跑得到 stage=${repeat_stage}" "重跑不应改变结论类型"
[[ "${first_id}" == "${repeat_id}" && -n "${first_id}" ]] || gate_failed "幂等" \
  "重跑产生了不同的候选：${first_id} vs ${repeat_id}" \
  "同一 generationKey 必须复用候选；否则一次故障会在评审台上刷出多条重复知识"
ok "幂等：重跑复用同一条候选 ${first_id}（stage=${repeat_stage}）"

# ── 闸门 10/11：在线 lane 也通，且没有编造 ─────────────────────────
# 知识生产 lane 通了不等于报障人能拿到东西。/incidents 只按错误码路由，
# 无码故障会落到 miss-path Agent，而它默认关闭 —— 于是蓝图点名的第一个场景
# 在线上是关着的。场景入口用 DETERMINISTIC + SCENARIO_PLAYBOOK 把它打开，
# 零 LLM，不需要 Agent。
online=$(cat <<JSON
{"system":"${SYSTEM}","service":"${SERVICE}","title":"冒烟：会话消息发送失败",
 "severity":"P1","customerRef":"tenant-42"}
JSON
)
created="$(call POST "/scenarios/${SCENARIO_KEY}/diagnoses" "${online}")"
[[ "$(http_code)" == "200" ]] || gate_failed "在线 lane 能接住" \
  "POST /scenarios/${SCENARIO_KEY}/diagnoses 返回 HTTP $(http_code)：$(echo "${created}" | jq -r '.msg // .' | head -c 200)" \
  "确认 ${SYSTEM}:scenario:${SCENARIO_KEY} 已有 approved 的 SCENARIO Playbook"
online_id="$(echo "${created}" | jq -r '.data.diagnosis.diagnosisId // empty')"
[[ -n "${online_id}" ]] || gate_failed "在线 lane 能接住" "响应里没有 diagnosisId" \
  "检查场景入口的响应结构"
ok "在线诊断已接住：${online_id}"

online_code="$(echo "${created}" | jq -r '.data.diagnosis.incident.errorCode // "null"')"
online_conclusion="$(echo "${created}" | jq -r '.data.diagnosis.conclusionType')"
online_mode="$(echo "${created}" | jq -r '.data.diagnosis.investigationMode')"
online_authority="$(echo "${created}" | jq -r '.data.diagnosis.routeAuthority')"
[[ "${online_code}" == "null" ]] || gate_failed "没有编造" \
  "诊断带上了 errorCode=${online_code}，但报障根本没有错误码" \
  "无码故障不得被补上一个猜来的码——那会让它混进确定性错误码权威"
[[ "${online_conclusion}" == "INSUFFICIENT_EVIDENCE" ]] || gate_failed "没有编造" \
  "结论是 ${online_conclusion}，但取证尚未执行" \
  "指定场景是在选哪份只读证据计划适用，不是断言原因；
         点个场景名就能拿到结论的话，route/authority 的拆分就是装饰"
[[ "${online_mode}" == "SCENARIO_PLAYBOOK" && "${online_authority}" == "EXPLICIT" ]] \
  || gate_failed "没有编造" \
     "mode=${online_mode} authority=${online_authority}，期望 SCENARIO_PLAYBOOK + EXPLICIT" \
     "人显式选定的场景必须记为 EXPLICIT；模型提议注册键才是 MODEL_PROPOSED，两者要能分开统计"
ok "没有编造：errorCode=null，结论=${online_conclusion}，${online_mode} + ${online_authority}"

online_status="$(echo "${created}" | jq -r '.data.diagnosis.status')"
[[ "${online_status}" == "NEEDS_INVESTIGATION" ]] || gate_failed "在线 lane 能接住" \
  "诊断状态是 ${online_status}，期望 NEEDS_INVESTIGATION" \
  "场景入口只创建 Diagnosis 归属，取证尚未执行；它不该以别的状态落地"
dim "  └ 状态=${online_status}：已接住，等待取证。场景侧证据执行件尚未实现，"
dim "    这条诊断目前无法被确认或关闭——这是已知缺口，不是本次跑通的一部分。"

echo
blue "无码路冒烟通过：一条无错误码报障走到了一条可评审的知识；在线 lane 能接住同一个故障。"
dim  "注意：全程 fixture，归纳来源 provider=${provider}。"
dim  "这证明学习环可走，不证明它归纳得对——后者要看与人工解法的差异报告和 T7 真实证据。"
