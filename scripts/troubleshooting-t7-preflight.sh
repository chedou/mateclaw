#!/usr/bin/env bash
#
# T7 内网窗口预检：只读，不发凭据，不碰观测云。
#
# Why this exists. 剩下的路是 T7 内网窗口 → owner ACCEPTED → 开放真源采样 →
# 攒样本，而第一步是**最贵、最难重来**的一格：要约到 owner、要内网、要受控运行时
# Key。TODO §3 早就写过这条风险——「窗口拿到了也用不上，操作员卡在同样的配置迷宫
# 里」。一次窗口废掉，重排是以周计的。
#
# 所以这支脚本不做验收，它只回答窗口开始前唯一值得问的问题：
#
#     现在进窗口，会不会卡在某一格上？卡在哪一格？下一步动作是什么？
#
# 它把服务端自己给出的 blockers 原样打出来——那是服务端对"缺什么"的说法，
# 比脚本自己复述一遍准。
#
# 四条刻意的约束：
#   1. **只读。** 只发 GET。一支能顺手把 Key 提交出去的预检，比没有预检更糟。
#   2. **绝不在夹具环境里报"就绪"。** 在没配 Guance 的机器上跑，它必须说没就绪；
#      一个什么都没配还能通过的检查，就是一个空转的闸门。
#   3. **验收清单模板一律输出 false。** 那七项是 owner 的书面确认，
#      预填 true 等于机器替人签字。
#   4. **没有 20–30 条服务端冻结目标就不报窗口就绪。** 操作员计划只能引用服务端
#      已绑定到精确 selector / candidate / request / query contract 的 targetId，再补历史时间；
#      不能自己填写 searchTerm 来宣称某个 selector 可执行。
#
#   ./scripts/troubleshooting-t7-preflight.sh --gates
#   T7_SEED_PLAN_FILE=/secure/local/t7-window-plan.json \
#     ./scripts/troubleshooting-t7-preflight.sh
#
# Exit codes: 0 = 可以开窗口；1 = 会卡在某一格（已指明）；2 = 脚本自己跑不起来。

set -euo pipefail

BASE_URL="${MATECLAW_BASE_URL:-http://127.0.0.1:18088}"
TOKEN="${MATECLAW_TOKEN:-}"
USERNAME="${MATECLAW_USERNAME:-}"
PASSWORD="${MATECLAW_PASSWORD:-}"
WORKSPACE_ID="${MATECLAW_WORKSPACE_ID:-1}"
SYSTEM="${T7_SYSTEM:-CSDP}"
SERVICE="${T7_SERVICE:-csdp-session-service}"
SEED_PLAN_FILE="${T7_SEED_PLAN_FILE:-}"
SEED_PLAN_COUNT=0
PREFLIGHT_TEMP_DIR=""
SEED_PLAN_SNAPSHOT=""
HASH_TOOL=""
API="${BASE_URL}/api/v1/troubleshooting"

# The three the Evidence Spine actually needs. incident_impact is real but not on
# this critical path, so a block there must not read as a block on the window.
CORE_SIGNALS=(log_search log_trace_bundle contrast_sample)

blue() { printf '\033[34m%s\033[0m\n' "$1"; }
red()  { printf '\033[31m%s\033[0m\n' "$1"; }
dim()  { printf '\033[90m%s\033[0m\n' "$1"; }
ok()   { printf '\033[32m  ✓\033[0m %s\n' "$1"; }
warn() { printf '\033[33m  !\033[0m %s\n' "$1"; }

# jq accepts duplicate object keys by keeping the last value. That makes a
# schema check look green even when the bytes also carry an overridden secret,
# query, or contract value. Python's stdlib parser can reject duplicates at
# every nesting level and json.load also rejects a second root value.
strict_json() {
  python3 -c '
import json
import sys

def reject_duplicates(pairs):
    value = {}
    for key, item in pairs:
        if key in value:
            raise ValueError(f"duplicate JSON key: {key}")
        value[key] = item
    return value

def reject_constant(value):
    raise ValueError(f"non-standard JSON number: {value}")

json.load(
    sys.stdin,
    object_pairs_hook=reject_duplicates,
    parse_constant=reject_constant,
)
' >/dev/null 2>&1
}

cleanup() {
  if [[ -n "${SEED_PLAN_SNAPSHOT}" ]]; then
    rm -f "${SEED_PLAN_SNAPSHOT}" 2>/dev/null || true
  fi
  if [[ -n "${PREFLIGHT_TEMP_DIR}" ]]; then
    rmdir "${PREFLIGHT_TEMP_DIR}" 2>/dev/null || true
  fi
}
trap cleanup EXIT

blocked() {
  local stage="$1" why="$2" next="$3"
  echo
  red "  ✗ 窗口会卡在这一格：${stage}"
  printf '    服务端说：%s\n' "${why}"
  printf '    下一步：  %s\n' "${next}"
  echo
  dim "  这支脚本不做验收，只避免把一次内网窗口浪费在配置上。"
  dim "  把上面这一格解决掉再约窗口，比进去之后现查要便宜得多。"
  exit 1
}

print_gates() {
  blue "进 T7 内网窗口之前，需要依次确认的格子"
  cat <<'GATES'

  1. 服务可达且能认证        预检自己得先跑得起来
  2. Guance adapter 已启用    adapterEnabled / endpointConfigured / credentialState。
                            默认 dev 档是关的——这正是本机跑应当停在的地方
  3. 三个核心 signal 已路由    log_search / log_trace_bundle / contrast_sample 必须都
                            routedToGuance；incident_impact 不在关键路上，不算阻塞
  4. binding 指纹可唯一计算    currentBindingFingerprint 不为 null。
                            算不出指纹就没有东西可供 owner 验收，窗口里再补最贵
  5. 20–30 条录制目标        运行服务必须先返回 20–30 个 server-owned target；每个
                            target 已冻结 selector / candidate / request / binding。
                            操作员计划只补精确历史时间和来源引用，不能自造查询映射
  6. owner 验收状态          NOT_ACCEPTED = 窗口要做的事；STALE = 配置变过，要重做；
                            ACCEPTED = 已完成，窗口只需做剩下的项
  7. 真源采样闸门            未验收前必须是关着的。这一格**期望它关着**——
                            它开着才是问题

  第 2 格与第 7 格是反向的：本机跑，2 必须停、7 必须关。
  一个在什么都没配的机器上还能全绿的预检，是空转的闸门。
GATES
}

if [[ "${1:-}" == "--gates" ]]; then
  print_gates
  exit 0
fi

for tool in curl jq head mktemp python3 wc; do
  command -v "${tool}" >/dev/null 2>&1 || { red "缺少 ${tool}"; exit 2; }
done
if command -v shasum >/dev/null 2>&1; then
  HASH_TOOL="shasum"
elif command -v sha256sum >/dev/null 2>&1; then
  HASH_TOOL="sha256sum"
else
  red "缺少 shasum / sha256sum，无法冻结窗口计划指纹"
  exit 2
fi

blue "MateClaw 智能排障 · T7 内网窗口预检（只读）"
dim  "服务：${BASE_URL}   workspace=${WORKSPACE_ID}   目标=${SYSTEM}/${SERVICE}"
echo

# ── 格 1：服务可达且能认证 ──────────────────────────────────────────
if [[ -z "${TOKEN}" && -n "${USERNAME}" ]]; then
  login_response=""
  if login_response="$(curl -sS -X POST "${BASE_URL}/api/v1/auth/login" \
      -H 'Content-Type: application/json' \
      --data "{\"username\":\"${USERNAME}\",\"password\":\"${PASSWORD}\"}" \
      2>/dev/null)"; then
    if printf '%s' "${login_response}" | strict_json; then
      TOKEN="$(jq -r '.data.token // empty' <<<"${login_response}" 2>/dev/null || true)"
    fi
  fi
fi
[[ -n "${TOKEN}" ]] || blocked "服务可达且能认证" \
  "未能取得 token（${BASE_URL}）" \
  "确认服务已启动；用 MATECLAW_USERNAME/PASSWORD 或 MATECLAW_TOKEN 提供身份"

auth=(-H "Authorization: Bearer ${TOKEN}" -H "X-Workspace-Id: ${WORKSPACE_ID}")
get() { curl -sS "${auth[@]}" "${API}$1" 2>/dev/null || echo '{}'; }
blockers_of() { echo "$1" | jq -r '[.data.blockers[]?] | join("；") // ""'; }

ok "服务可达，身份可用"

# ── 格 2：Guance adapter 已启用 ─────────────────────────────────────
readiness="$(get "/evidence/readiness?system=${SYSTEM}&service=${SERVICE}")"
if ! printf '%s' "${readiness}" | strict_json; then
  blocked "Guance adapter 已启用" \
    "GET /evidence/readiness 未返回严格的单根 JSON（重复键或尾随根值会被拒绝）" \
    "修复服务端响应或代理篡改；预检不会让 jq 静默覆盖同名字段"
fi
status="$(echo "${readiness}" | jq -r '.data.status // empty')"
[[ -n "${status}" ]] || blocked "Guance adapter 已启用" \
  "GET /evidence/readiness 没有返回可读的 status" \
  "确认接口可用且 workspace/system/service 参数正确"

adapter_enabled="$(echo "${readiness}" | jq -r '.data.adapterEnabled')"
endpoint_configured="$(echo "${readiness}" | jq -r '.data.endpointConfigured')"
credential_state="$(echo "${readiness}" | jq -r '.data.credentialState')"

if [[ "${adapter_enabled}" != "true" || "${endpoint_configured}" != "true" ]]; then
  blocked "Guance adapter 已启用" \
    "status=${status}；adapterEnabled=${adapter_enabled}，endpointConfigured=${endpoint_configured}。$(blockers_of "${readiness}")" \
    "这台机器还没接真源。若这是本机 dev/demo 环境，**停在这里是对的**——
             预检的作用就是不让你带着这个状态进窗口。
             要接真源：在目标环境配置 Guance 端点与受控运行时 Key 后重跑本脚本"
fi
ok "Guance adapter 已启用，端点已配置（credentialState=${credential_state}）"

# ── 格 3：三个核心 signal 已路由 ────────────────────────────────────
unrouted=""
for signal in "${CORE_SIGNALS[@]}"; do
  routed="$(echo "${readiness}" | jq -r --arg s "${signal}" \
    '[.data.signals[]? | select(.signalKind == $s) | .routedToGuance] | first // false')"
  [[ "${routed}" == "true" ]] || unrouted+="${signal} "
done
[[ -z "${unrouted}" ]] || blocked "三个核心 signal 已路由" \
  "未路由到 Guance：${unrouted}" \
  "Evidence Spine 是 log_search → log_trace_bundle → contrast_sample 三段；
             缺任何一段，窗口里跑出来的都不是完整竖线。先补路由配置"

not_ready="$(echo "${readiness}" | jq -r --argjson core "$(printf '%s\n' "${CORE_SIGNALS[@]}" | jq -R . | jq -s .)" \
  '[.data.signals[]? | select(.signalKind as $k | $core | index($k))
    | select(.status == "UNAUTHORIZED" or .status == "INVALID_BINDING")
    | "\(.signalKind)=\(.status)"] | join(", ")')"
[[ -z "${not_ready}" ]] || blocked "三个核心 signal 已路由" \
  "已路由但绑定不可用：${not_ready}" \
  "资产授权或 binding 配置还没到位；这在窗口里现改最贵，先在窗口外解决"
ok "三个核心 signal 均已路由且绑定可用"

# ── 格 4：binding 指纹可唯一计算 ────────────────────────────────────
acceptance="$(get "/evidence/guance/acceptance?system=${SYSTEM}&service=${SERVICE}")"
if ! printf '%s' "${acceptance}" | strict_json; then
  blocked "binding 指纹可唯一计算" \
    "GET /evidence/guance/acceptance 未返回严格的单根 JSON" \
    "修复服务端响应；重复键或尾随根值不能参与 owner 验收"
fi
fingerprint="$(echo "${acceptance}" | jq -r '.data.currentBindingFingerprint // empty')"
accept_status="$(echo "${acceptance}" | jq -r '.data.status // empty')"
[[ -n "${fingerprint}" ]] || blocked "binding 指纹可唯一计算" \
  "status=${accept_status}，currentBindingFingerprint=null。$(blockers_of "${acceptance}")" \
  "算不出唯一指纹就没有东西可供 owner 验收——验收是绑在指纹上的。
             先让资产、核心路由和 binding 配置唯一确定下来"
ok "binding 指纹可计算：${fingerprint:0:16}…"

# ── 格 5：20–30 条服务端冻结录制目标 ──────────────────────────────
# The running service, not the operator file and not this checkout, owns the
# selector → candidate → request → Guance binding identity. The local plan may
# only select a targetId and add a historical timestamp/reference.
target_catalog="$(get "/evidence/guance/recording-targets?system=${SYSTEM}&service=${SERVICE}")"
if ! printf '%s' "${target_catalog}" | strict_json; then
  blocked "20–30 条服务端冻结录制目标" \
    "GET /evidence/guance/recording-targets 未返回严格的单根 JSON" \
    "修复服务端响应；重复键或尾随根值不能被 jq 覆盖后冒充冻结目录"
fi
search_binding="$(echo "${readiness}" | jq -r '
  [.data.signals[]? | select(.signalKind == "log_search") | .bindingRef] | first // ""')"
trace_binding="$(echo "${readiness}" | jq -r '
  [.data.signals[]? | select(.signalKind == "log_trace_bundle") | .bindingRef] | first // ""')"
contrast_binding="$(echo "${readiness}" | jq -r '
  [.data.signals[]? | select(.signalKind == "contrast_sample") | .bindingRef] | first // ""')"

if ! jq -e \
  --arg system "${SYSTEM}" \
  --arg service "${SERVICE}" \
  --arg search "${search_binding}" \
  --arg trace "${trace_binding}" \
  --arg contrast "${contrast_binding}" '
  .data as $data
  | ($data | type == "object")
  and (($data | keys) == [
    "asOfEpochSeconds", "blockers", "catalogFingerprint", "contractVersion",
    "executableTargetCount", "frozenTargetCount", "service", "system", "targets"
  ])
  and $data.contractVersion == "t7-guance-recording-target-catalog.v1"
  and $data.system == $system
  and $data.service == $service
  and ($data.catalogFingerprint | type == "string" and test("^[a-f0-9]{64}$"))
  # The global Long serializer intentionally emits decimal strings for
  # browser precision. Epoch seconds stay within ten digits for this contract;
  # accepting a JSON number here would make the CI stub differ from production.
  and ($data.asOfEpochSeconds
       | type == "string" and test("^[1-9][0-9]{0,9}$"))
  and ($data.frozenTargetCount | type == "number" and . >= 0 and floor == .)
  and ($data.executableTargetCount | type == "number" and . >= 0 and floor == .)
  and $data.frozenTargetCount >= $data.executableTargetCount
  and ($data.targets | type == "array")
  and ($data.blockers | type == "array" and all(.[]; type == "string"))
  and $data.executableTargetCount == ($data.targets | length)
  and all($data.targets[];
    type == "object"
    and (keys == [
      "bindingRefs", "candidateFingerprint", "candidateReference",
      "requestFingerprint", "requiredEvidenceRequestId", "searchTerm", "selectorKey",
      "service", "system", "targetId", "window"
    ])
    and ([.targetId, .system, .service, .selectorKey, .candidateReference,
          .candidateFingerprint, .requiredEvidenceRequestId, .requestFingerprint,
          .searchTerm, .window] | all(type == "string" and length > 0))
    and .system == $system
    and .service == $service
    and (.targetId | test("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$"))
    and (.selectorKey | test("^csdp:[A-Za-z0-9_]+$"))
    and (.candidateReference | test("^[A-Za-z0-9][A-Za-z0-9._:/#-]{0,255}$"))
    and (.candidateFingerprint | test("^[a-f0-9]{64}$"))
    and (.requiredEvidenceRequestId | test("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$"))
    and (.requestFingerprint | test("^[a-f0-9]{64}$"))
    and (.searchTerm | test("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$"))
    and (.window | test("^-[1-9][0-9]{0,5}(s|m|h|d)$"))
    and (.bindingRefs | keys == ["contrast_sample", "log_search", "log_trace_bundle"])
    and .bindingRefs.log_search == $search
    and .bindingRefs.log_trace_bundle == $trace
    and .bindingRefs.contrast_sample == $contrast
  )
  and ([$data.targets[].targetId] | unique | length) == ($data.targets | length)
  and ([$data.targets[].selectorKey] | unique | length) == ($data.targets | length)
  and ([$data.targets[].candidateFingerprint] | unique | length) == ($data.targets | length)
  and ([$data.targets[].requestFingerprint] | unique | length) == ($data.targets | length)
' <<<"${target_catalog}" >/dev/null; then
  blocked "20–30 条服务端冻结录制目标" \
    "GET /evidence/guance/recording-targets 未返回与当前运行 binding 严格匹配的 v1 目录" \
    "先修复服务端冻结目录或部署版本；操作者自带 selector/searchTerm 不能替代服务端查询合同"
fi

invalid_window=""
while IFS= read -r planned_window; do
  if [[ ! "${planned_window}" =~ ^-([1-9][0-9]{0,5})(s|m|h|d)$ ]]; then
    invalid_window="${planned_window}"
    break
  fi
  window_value=$((10#${BASH_REMATCH[1]}))
  case "${BASH_REMATCH[2]}" in
    s) window_seconds=${window_value} ;;
    m) window_seconds=$((window_value * 60)) ;;
    h) window_seconds=$((window_value * 3600)) ;;
    d) window_seconds=$((window_value * 86400)) ;;
  esac
  if (( window_seconds > 86400 )); then
    invalid_window="${planned_window}"
    break
  fi
done < <(jq -r '.data.targets[].window' <<<"${target_catalog}")
[[ -z "${invalid_window}" ]] || blocked "20–30 条服务端冻结录制目标" \
  "服务端 target window=${invalid_window} 不是 1 秒到 24 小时的有界相对时间" \
  "修复服务端 target catalog；窗口计划不能覆盖 server-owned 查询预算"

catalog_target_count="$(jq -r '.data.targets | length' <<<"${target_catalog}")"
catalog_frozen_count="$(jq -r '.data.frozenTargetCount' <<<"${target_catalog}")"
catalog_fingerprint="$(jq -r '.data.catalogFingerprint' <<<"${target_catalog}")"
catalog_as_of="$(jq -r '.data.asOfEpochSeconds' <<<"${target_catalog}")"
if (( catalog_target_count < 20 )); then
  blocked "20–30 条服务端冻结录制目标" \
    "当前 scope 仅有 ${catalog_target_count} 个可执行新目标（冻结 ${catalog_frozen_count} 个）。$(blockers_of "${target_catalog}")" \
    "先在服务端目录为至少 20 个新 D1 selector 冻结精确 candidate/request 指纹、查询键和当前 binding；
             现有 SendMsg 合同已录制，不能重复凑数，也不能用任意 D1 selector 复用同一查询"
fi
ok "服务端返回 ${catalog_target_count} 个与当前 binding 匹配的未录制目标"
dim "目标目录 SHA-256：${catalog_fingerprint}"

[[ -n "${SEED_PLAN_FILE}" ]] || blocked "20–30 条窗口执行计划" \
  "未设置 T7_SEED_PLAN_FILE；服务端目标存在，但没有冻结本次历史故障批次" \
  "准备 t7-recording-window-plan.v1：每条只含 targetId、精确 occurredAt 与唯一 sourceReference"
[[ -f "${SEED_PLAN_FILE}" && -r "${SEED_PLAN_FILE}" ]] || blocked "20–30 条窗口执行计划" \
  "计划文件不存在或不可读：${SEED_PLAN_FILE}" \
  "修正 T7_SEED_PLAN_FILE；清单只保存在受控本地，不要放凭据、查询或原始日志"

umask 077
PREFLIGHT_TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/mateclaw-t7-preflight.XXXXXX")" \
  || { red "无法创建权限受限的计划快照目录"; exit 2; }
SEED_PLAN_SNAPSHOT="${PREFLIGHT_TEMP_DIR}/plan.json"
if ! head -c 131073 "${SEED_PLAN_FILE}" > "${SEED_PLAN_SNAPSHOT}"; then
  blocked "20–30 条窗口执行计划" \
    "无法有界读取计划文件：${SEED_PLAN_FILE}" \
    "检查文件权限；预检只会验证一次性 mode-600 快照"
fi
plan_bytes="$(wc -c < "${SEED_PLAN_SNAPSHOT}")"
plan_bytes="${plan_bytes//[[:space:]]/}"
if (( plan_bytes > 131072 )); then
  blocked "20–30 条窗口执行计划" \
    "计划文件至少 ${plan_bytes} bytes，超过 128 KiB 上限" \
    "计划只保存 20–30 条 targetId / 时间 / 引用；删除正文、响应、日志和其他内容"
fi

if ! strict_json < "${SEED_PLAN_SNAPSHOT}"; then
  blocked "20–30 条窗口执行计划" \
    "计划快照不是严格的单根 JSON（重复键或尾随根值会被拒绝）" \
    "先在窗口外修复 JSON；不要依赖 jq 的同名键覆盖行为"
fi

plan_contract="$(jq -r '.contractVersion // empty' "${SEED_PLAN_SNAPSHOT}")"
[[ "${plan_contract}" == "t7-recording-window-plan.v1" ]] || blocked "20–30 条窗口执行计划" \
  "contractVersion=${plan_contract:-<missing>}，只接受 t7-recording-window-plan.v1" \
  "使用当前窗口计划合同，避免旧清单绕过服务端目标与历史时间约束"

if ! jq -e '
  type == "object"
  and (keys == ["contractVersion", "seeds"])
  and (.seeds | type == "array")
' "${SEED_PLAN_SNAPSHOT}" >/dev/null; then
  blocked "20–30 条窗口执行计划" \
    "根对象只允许 contractVersion / seeds，且 seeds 必须是数组" \
    "删除额外字段；API Key、DQL、selector、原始日志和聚合结果都不属于操作员计划"
fi

SEED_PLAN_COUNT="$(jq -r '.seeds | length' "${SEED_PLAN_SNAPSHOT}")"
if (( SEED_PLAN_COUNT < 20 || SEED_PLAN_COUNT > 30 )); then
  blocked "20–30 条窗口执行计划" \
    "当前计划 ${SEED_PLAN_COUNT} 条；窗口目标必须在 20–30 条之间" \
    "窗口不是单次 Demo；在预约 owner 前补齐目标，超过 30 条则拆成下一批"
fi

if ! jq -e '
  all(.seeds[];
    type == "object"
    and (keys == ["occurredAt", "sourceReference", "targetId"])
    and ([.targetId, .occurredAt, .sourceReference]
         | all(type == "string" and length > 0))
    and (.targetId | test("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$"))
    and (.sourceReference | test("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}$"))
    and (.occurredAt | test("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$"))
    and ((try (.occurredAt | fromdateiso8601) catch null) != null)
  )
' "${SEED_PLAN_SNAPSHOT}" >/dev/null; then
  blocked "20–30 条窗口执行计划" \
    "条目字段越界，或 targetId / occurredAt / sourceReference 不安全" \
    "每条只保留三个白名单字段；时间必须是有效的 UTC RFC3339 整秒"
fi

future_times="$(jq -r --argjson asOf "${catalog_as_of}" '
  [.seeds[].occurredAt
   | select((fromdateiso8601) > $asOf)]
  | join(", ")
' "${SEED_PLAN_SNAPSHOT}")"
[[ -z "${future_times}" ]] || blocked "20–30 条窗口执行计划" \
  "occurredAt 晚于运行服务时间：${future_times}" \
  "批次只能引用已经发生的历史故障；若环境时钟错误，先校时再重跑"

target_count="$(jq -r '[.seeds[].targetId] | unique | length' "${SEED_PLAN_SNAPSHOT}")"
reference_count="$(jq -r '[.seeds[].sourceReference] | unique | length' "${SEED_PLAN_SNAPSHOT}")"
if (( target_count != SEED_PLAN_COUNT || reference_count != SEED_PLAN_COUNT )); then
  blocked "20–30 条窗口执行计划" \
    "targetId 或 sourceReference 存在重复" \
    "一个 server-owned target 只取一份聚合正例；重复目标不能凑批次分母"
fi

unknown_targets="$(jq -nr \
  --slurpfile plan "${SEED_PLAN_SNAPSHOT}" \
  --argjson catalog "${target_catalog}" '
    [$plan[0].seeds[].targetId]
    - [$catalog.data.targets[].targetId]
    | join(", ")
  ')"
[[ -z "${unknown_targets}" ]] || blocked "20–30 条窗口执行计划" \
  "计划引用了当前服务端目录之外的 targetId：${unknown_targets}" \
  "只从 GET /evidence/guance/recording-targets 返回的 targetId 选取；不要手写 selector/searchTerm"

plan_fingerprint=""
if [[ "${HASH_TOOL}" == "shasum" ]]; then
  read -r plan_fingerprint _ < <(shasum -a 256 "${SEED_PLAN_SNAPSHOT}")
else
  read -r plan_fingerprint _ < <(sha256sum "${SEED_PLAN_SNAPSHOT}")
fi
ok "${SEED_PLAN_COUNT} 条窗口执行项已冻结：均引用服务端目标，历史时间与来源引用唯一"
dim "计划 SHA-256：${plan_fingerprint}（窗口记录应同时引用目标目录与计划指纹）"

# ── 格 6：owner 验收状态 ────────────────────────────────────────────
case "${accept_status}" in
  ACCEPTED)
    matched="$(echo "${acceptance}" | jq -r \
      '(.data.acceptance.bindingFingerprint // "") == (.data.currentBindingFingerprint // "x")')"
    if [[ "${matched}" == "true" ]]; then
      ok "owner 已对当前指纹验收——T7 这一段已完成"
    else
      warn "状态是 ACCEPTED，但验收指纹与当前指纹不一致；按 STALE 处理"
      accept_status=STALE
    fi
    ;;
  NOT_ACCEPTED) warn "尚未验收——这正是窗口里要做的事" ;;
  STALE)        warn "配置在验收后变过，旧验收已失效，需要重做" ;;
  *)            blocked "owner 验收状态" \
                  "status=${accept_status}。$(blockers_of "${acceptance}")" \
                  "先解决上面的 blocker，再谈验收" ;;
esac

# ── 格 7：真源采样闸门（期望它关着） ────────────────────────────────
if [[ "${accept_status}" == "ACCEPTED" ]]; then
  ok "真源采样已开放（验收已完成）——按计划采集 ${SEED_PLAN_COUNT} 条聚合正例"
else
  ok "真源采样仍然关着——未验收前这是对的，不是故障"
fi

echo
if [[ "${accept_status}" == "ACCEPTED" ]]; then
  blue "预检通过：T7 验收已完成，下一步按冻结清单采集 ${SEED_PLAN_COUNT} 条，不是再跑单次 Demo。"
  exit 0
fi

blue "预检通过：配置已就位，可以约窗口。"
dim  "窗口里 owner 要做的事（清单原文见 runbook §6）："
cat <<EOF

  POST ${API}/evidence/guance/acceptance
  X-Workspace-Id: ${WORKSPACE_ID}

  {
    "system": "${SYSTEM}",
    "service": "${SERVICE}",
    "searchTerm": "<按历史故障填>",
    "window": "-15m",
    "occurredAt": "<历史故障时间, RFC3339>",
    "checklist": {
      "measurementAndFieldsVerified": false,
      "indexVerified": false,
      "psIdJoinVerified": false,
      "timestampUnitVerified": false,
      "timeWindowVerified": false,
      "dqlLatencyReviewed": false,
      "legacyRouteConflictReviewed": false
    }
  }
EOF
echo
dim "七项一律输出 false 是刻意的：那是 owner 的书面确认，"
dim "逐项真的核对过才改成 true。预填 true 等于机器替人签字。"
dim "请求里不要带指纹、计数、PS ID 或 actor——这些由服务端重算，带了会被拒。"
