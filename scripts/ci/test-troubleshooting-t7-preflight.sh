#!/usr/bin/env bash
#
# T7 预检自己的回归。真实窗口只能用一次，所以 CI 必须同时证明：
#   - 没有 server-owned 目标、计划或验收时会 fail closed；
#   - 只有精确运行 binding 对得上的 20–30 个 targetId 才能进入 ready path；
#   - 操作员文件只能补历史时间，不能发明 selector/searchTerm/query；
#   - 校验与 SHA-256 使用同一个 mode-600 快照，原文件中途替换也不能换掉已验证字节。

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PREFLIGHT="${ROOT_DIR}/scripts/troubleshooting-t7-preflight.sh"
INVENTORY="${ROOT_DIR}/mateclaw-server/src/main/resources/troubleshooting/knowledge/csdp-d1-error-code-selectors.json"
PORT="${T7_STUB_PORT:-18099}"
STATE_FILE="$(mktemp -t t7-stub-state.XXXXXX)"
STUB_LOG="$(mktemp -t t7-stub-log.XXXXXX)"
OUT_FILE="$(mktemp -t t7-preflight-out.XXXXXX)"
SEED_PLAN="$(mktemp -t t7-seed-plan.XXXXXX)"
REPLACEMENT_PLAN="$(mktemp -t t7-seed-replacement.XXXXXX)"
TOCTOU_TMPDIR="$(mktemp -d -t t7-preflight-tmp.XXXXXX)"
NO_HASH_PATH="$(mktemp -d -t t7-no-hash-path.XXXXXX)"
STATE_UPDATE="$(mktemp -t t7-stub-state-update.XXXXXX)"
STUB_PID=""
RECORDING_BATCH='{}'
AS_OF_EPOCH="$(jq -nr '"2026-08-02T00:00:00Z" | fromdateiso8601')"

cleanup() {
  if [[ -n "${STUB_PID}" ]]; then
    kill "${STUB_PID}" 2>/dev/null || true
    wait "${STUB_PID}" 2>/dev/null || true
  fi
  rm -f "${STATE_FILE}" "${STUB_LOG}" "${STUB_LOG}.py" \
    "${OUT_FILE}" "${SEED_PLAN}" "${REPLACEMENT_PLAN}"
  rm -f "${STATE_UPDATE}"
  rmdir "${TOCTOU_TMPDIR}" 2>/dev/null || true
  rm -f "${NO_HASH_PATH}"/* 2>/dev/null || true
  rmdir "${NO_HASH_PATH}" 2>/dev/null || true
}
trap cleanup EXIT

fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }

for tool in curl jq python3; do
  command -v "${tool}" >/dev/null 2>&1 \
    || { printf 'missing %s\n' "${tool}" >&2; exit 2; }
done
[[ -x "${PREFLIGHT}" ]] || fail "preflight script must exist and be executable"

cat > "${STUB_LOG}.py" <<'PY'
import json, os, sys
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import urlparse

STATE = sys.argv[1]

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def _send(self, payload):
        body = json.dumps({"code": 200, "msg": "ok", "data": payload}).encode()
        self._send_body(body)

    def _send_body(self, body):
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        if urlparse(self.path).path == "/api/v1/auth/login":
            self._send({"token": "stub-token"})
        else:
            self.send_error(404)

    def do_GET(self):
        state = json.load(open(STATE, encoding="utf-8"))
        path = urlparse(self.path).path
        if path.endswith("/evidence/readiness"):
            self._send(state["readiness"])
        elif path.endswith("/evidence/guance/acceptance"):
            self._send(state["acceptance"])
        elif path.endswith("/evidence/guance/recording-batches/current"):
            raw = state.get("recordingResponseRaw")
            if raw is None:
                self._send(state["recordingBatch"])
            else:
                self._send_body(raw.encode())
        else:
            self.send_error(404)

HTTPServer(("127.0.0.1", int(os.environ["PORT"])), Handler).serve_forever()
PY

PORT="${PORT}" python3 "${STUB_LOG}.py" "${STATE_FILE}" &
STUB_PID=$!

printf '{"readiness":{},"acceptance":{},"recordingBatch":{}}' > "${STATE_FILE}"
for _ in $(seq 1 50); do
  curl -sS -o /dev/null --max-time 1 \
    -X POST "http://127.0.0.1:${PORT}/api/v1/auth/login" 2>/dev/null && break
  sleep 0.2
done

signals() { # routed-core-signals...
  local routed=("$@") out="[]"
  for kind in log_search log_trace_bundle contrast_sample; do
    local is_routed=false status='"NOT_ROUTED"' binding=""
    case "${kind}" in
      log_search) binding="search-binding" ;;
      log_trace_bundle) binding="trace-binding" ;;
      contrast_sample) binding="contrast-binding" ;;
    esac
    for present in "${routed[@]}"; do
      if [[ "${present}" == "${kind}" ]]; then
        is_routed=true
        status='"READY_FOR_VALIDATION"'
      fi
    done
    out="$(jq -c \
      --arg k "${kind}" \
      --arg b "${binding}" \
      --argjson r "${is_routed}" \
      --argjson s "${status}" \
      '. + [{signalKind:$k, routedToGuance:$r, status:$s,
             bindingRef:$b, lastObservedAt:null, detail:""}]' <<<"${out}")"
  done
  echo "${out}"
}

recording_batch() { # count [mutation]
  local count="$1" mutation="${2:-}" selectors targets executable_count
  selectors="$(jq -c --argjson count "${count}" '.selectors[:$count]' "${INVENTORY}")"
  targets="$(jq -n \
    --argjson selectors "${selectors}" \
    --arg mutation "${mutation}" '
    $selectors | to_entries | map({
      targetId: ("target-" + ((.key + 1) | tostring)),
      system: "CSDP",
      service: (if .key < 10 then "service-a" else "service-b" end),
      scenarioKey: null,
      selectorKey: .value,
      bindingFingerprint: (if .key < 10 then ("a" * 64) else ("b" * 64) end),
      targetBindingFingerprint: (("c" * 64 + ((.key + 1) | tostring))[-64:]),
      executable: true,
      blockers: []
    }
    | if $mutation == "one-inexecutable" and .targetId == "target-20"
      then .executable = false
      | .blockers = ["frozen target bindings do not match the running bindings"]
      elif $mutation == "missing-fingerprint" and .targetId == "target-1"
      then .bindingFingerprint = null
      | .targetBindingFingerprint = null
      | .executable = false
      | .blockers = ["exact target binding cannot be uniquely fingerprinted"]
      elif $mutation == "duplicate-selector" and .targetId == "target-2"
      then .selectorKey = $selectors[0]
      elif $mutation == "extra-field" and .targetId == "target-1"
      then . + {apiKey: "must-never-enter-catalog"}
      else . end)
  ')"
  executable_count="$(jq -r '[.[] | select(.executable == true)] | length' <<<"${targets}")"
  RECORDING_BATCH="$(jq -n \
    --argjson targets "${targets}" \
    --argjson executableCount "${executable_count}" \
    --arg asOf "${AS_OF_EPOCH}" \
    --arg mutation "${mutation}" '
    {
      contractVersion: (if $mutation == "wrong-contract"
                        then "t7-guance-recording-batch-readiness.v1"
                        else "t7-guance-recording-batch-readiness.v2" end),
      batchId: ("t7-first-" + ("c" * 24)),
      workspaceId: (if $mutation == "wrong-workspace" then "2" else "1" end),
      catalogContractVersion: "t7-guance-recording-target-catalog.v1",
      catalogFingerprint: ("c" * 64),
      frozenTargetCount: ($targets | length),
      executableTargetCount: $executableCount,
      readyForOwnerAcceptance: (($targets | length) >= 20
                                and ($targets | length) <= 30
                                and $executableCount >= 20),
      targets: $targets,
      asOfEpochSeconds: (if $mutation == "numeric-as-of"
                         then ($asOf | tonumber) else $asOf end),
      blockers: (if $executableCount < 20
                 then ["fewer than 20 executable workspace targets"]
                 elif ($targets | length) > 30
                 then ["workspace first batch exceeds 30"]
                 else [] end)
    }
  ')"
}

state() { # signals-json fingerprint acceptStatus acceptedFingerprint
  local sig="$1" fp="$2" st="$3" accepted_fp="${4:-}"
  local acceptance
  acceptance="$(jq -n --arg st "${st}" --arg fp "${fp}" --arg afp "${accepted_fp}" '
    {status:$st,
      system:"CSDP", service:"csdp-session-service",
      currentBindingFingerprint: (if $fp == "" then null else $fp end),
      acceptance: (if $afp == "" then null else {bindingFingerprint:$afp} end),
      blockers: []}')"
  jq -n \
    --argjson sig "${sig}" \
    --argjson acc "${acceptance}" \
    --argjson batch "${RECORDING_BATCH}" '
    {readiness:{system:"CSDP", service:"csdp-session-service",
                 status:"READY_FOR_VALIDATION", adapterEnabled:true,
                 endpointConfigured:true, credentialState:"CONFIGURED",
                 uniqueAssetAuthorized:true, signals:$sig, blockers:[]},
      acceptance:$acc,
      recordingBatch:$batch,
      recordingResponseRaw:null}' > "${STATE_FILE}"
}

set_raw_recording_response() { # exact response bytes
  local raw="$1"
  jq --arg raw "${raw}" '.recordingResponseRaw = $raw' \
    "${STATE_FILE}" > "${STATE_UPDATE}"
  mv "${STATE_UPDATE}" "${STATE_FILE}"
}

seed_plan() { # count [mutation]
  local count="$1" mutation="${2:-}"
  jq -n \
    --argjson count "${count}" \
    --argjson batch "${RECORDING_BATCH}" \
    --arg mutation "${mutation}" '
    {
      contractVersion: (if $mutation == "wrong-contract"
                        then "t7-recording-window-plan.v0"
                        else "t7-recording-window-plan.v1" end),
      seeds: ([range(0; $count)] | map({
        targetId: (if . < ($batch.targets | length)
                   then $batch.targets[.].targetId
                   else "target-outside-catalog-\(.)" end),
        occurredAt: "2026-07-31T09:55:10Z",
        sourceReference: ("t7-window-seed-" + ((. + 1) | tostring))
      }))
    }
    | if $mutation == "duplicate-target" then .seeds[1].targetId = .seeds[0].targetId
      elif $mutation == "duplicate-reference" then .seeds[1].sourceReference = .seeds[0].sourceReference
      elif $mutation == "unknown-target" then .seeds[0].targetId = "target-not-frozen"
      elif $mutation == "invalid-time" then .seeds[0].occurredAt = "2026-99-99T99:99:99Z"
      elif $mutation == "future-time" then .seeds[0].occurredAt = "2099-01-01T00:00:00Z"
      elif $mutation == "extra-field" then .seeds[0].searchTerm = "operator-must-not-choose-this"
      elif $mutation == "dangerous-reference" then .seeds[0].sourceReference = "DF-API-KEY=secret"
      elif $mutation == "empty-reference" then .seeds[0].sourceReference = ""
      elif $mutation == "extra-root" then .apiKey = "must-never-enter-plan"
      else . end
  ' > "${SEED_PLAN}"
}

run_with_plan() { # plan-path -> exit code in RC, output in OUT_FILE
  local plan_path="$1"
  set +e
  MATECLAW_BASE_URL="http://127.0.0.1:${PORT}" MATECLAW_TOKEN=stub-token \
    T7_SEED_PLAN_FILE="${plan_path}" \
    "${PREFLIGHT}" > "${OUT_FILE}" 2>&1
  RC=$?
  set -e
}

run() { run_with_plan "${SEED_PLAN}"; }

expect_blocked_at() { # stage-substring
  [[ "${RC}" -eq 1 ]] || fail "expected the preflight to block, got exit ${RC}
$(cat "${OUT_FILE}")"
  grep -Fq -- "$1" "${OUT_FILE}" \
    || fail "expected to be blocked at: $1
$(cat "${OUT_FILE}")"
}

expect_ready() { # expected-substring
  [[ "${RC}" -eq 0 ]] || fail "expected the preflight to pass, got exit ${RC}
$(cat "${OUT_FILE}")"
  grep -Fq -- "$1" "${OUT_FILE}" \
    || fail "expected in a passing run: $1
$(cat "${OUT_FILE}")"
}

hash_file() {
  if command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

# ── 1–4 格：服务、adapter、核心 signal、binding 指纹 ───────────────
recording_batch 20
seed_plan 20

set +e
MATECLAW_BASE_URL="http://127.0.0.1:1" \
  MATECLAW_USERNAME=stub-user MATECLAW_PASSWORD=stub-password \
  "${PREFLIGHT}" > "${OUT_FILE}" 2>&1
RC=$?
set -e
expect_blocked_at "服务可达且能认证"
grep -Fq "未能取得 token" "${OUT_FILE}" \
  || fail "an unreachable service must produce the stage-1 recovery instruction"
printf 'ok  服务不可达 → 停在第 1 格\n'

jq -n --argjson batch "${RECORDING_BATCH}" '
  {readiness:{status:"DISABLED", adapterEnabled:false,
              endpointConfigured:false, credentialState:"NOT_INSPECTED",
              signals:[], blockers:["Guance adapter is disabled"]},
   acceptance:{status:"BLOCKED", currentBindingFingerprint:null,
               acceptance:null, blockers:[]},
   recordingBatch:$batch}' > "${STATE_FILE}"
run
expect_blocked_at "Guance adapter 已启用"
grep -Fq "Guance adapter is disabled" "${OUT_FILE}" \
  || fail "the server's own blocker text must be shown verbatim"
printf 'ok  adapter 关着 → 停在第 2 格，并原样打出服务端 blocker\n'

state "$(signals log_search log_trace_bundle)" "fp-1" "NOT_ACCEPTED"
run
expect_blocked_at "三个核心 signal 已路由"
grep -Fq "contrast_sample" "${OUT_FILE}" || fail "missing signal must be named"
printf 'ok  少 contrast_sample → 停在第 3 格并点名\n'

state "$(signals log_search log_trace_bundle contrast_sample)" "" "BLOCKED"
run
expect_blocked_at "binding 指纹可唯一计算"
printf 'ok  指纹为 null → 停在第 4 格\n'

# ── 第 5 格先信运行服务，不信操作者自报映射 ────────────────────────
recording_batch 0
state "$(signals log_search log_trace_bundle contrast_sample)" "fp-1" "NOT_ACCEPTED"
seed_plan 20
run
expect_blocked_at "20–30 条服务端冻结录制目标"
grep -Fq "全 workspace 仅有 0 个可执行目标" "${OUT_FILE}" \
  || fail "an empty workspace batch must state the real denominator"
printf 'ok  workspace 批次没有 20 个目标 → 不把任一 scope 冒充全局分母\n'

for batch_mutation in \
  one-inexecutable missing-fingerprint duplicate-selector extra-field \
  wrong-contract wrong-workspace numeric-as-of; do
  recording_batch 20 "${batch_mutation}"
  state "$(signals log_search log_trace_bundle contrast_sample)" "fp-1" "NOT_ACCEPTED"
  seed_plan 20
  run
  expect_blocked_at "20–30 条服务端冻结录制目标"
done
recording_batch 31
state "$(signals log_search log_trace_bundle contrast_sample)" "fp-1" "NOT_ACCEPTED"
seed_plan 20
run
expect_blocked_at "20–30 条服务端冻结录制目标"
printf 'ok  10+10 中任一目标失效会降到 19；错误 workspace、31 条超界及坏合同均阻断\n'

recording_batch 20
state "$(signals log_search log_trace_bundle contrast_sample)" "fp-1" "NOT_ACCEPTED"

valid_recording_response="$(jq -nc \
  --argjson data "${RECORDING_BATCH}" \
  '{code:200,msg:"ok",data:$data}')"
duplicate_recording_response="${valid_recording_response/\"contractVersion\":\"t7-guance-recording-batch-readiness.v2\"/\"contractVersion\":\"evil\",\"contractVersion\":\"t7-guance-recording-batch-readiness.v2\"}"
set_raw_recording_response "${duplicate_recording_response}"
seed_plan 20
run
expect_blocked_at "20–30 条服务端冻结录制目标"

state "$(signals log_search log_trace_bundle contrast_sample)" "fp-1" "NOT_ACCEPTED"
set_raw_recording_response "${valid_recording_response}{\"ignored\":true}"
run
expect_blocked_at "20–30 条服务端冻结录制目标"
printf 'ok  workspace 批次响应的重复键与尾随根值均在 jq 读取前阻断\n'

state "$(signals log_search log_trace_bundle contrast_sample)" "fp-1" "NOT_ACCEPTED"

set +e
MATECLAW_BASE_URL="http://127.0.0.1:${PORT}" MATECLAW_TOKEN=stub-token \
  "${PREFLIGHT}" > "${OUT_FILE}" 2>&1
RC=$?
set -e
expect_blocked_at "20–30 条窗口执行计划"
printf 'ok  缺本次计划 → 不把 workspace 批次容量冒充历史样本已准备\n'

run_with_plan "${SEED_PLAN}.does-not-exist"
expect_blocked_at "20–30 条窗口执行计划"
printf 'ok  计划路径不存在 → 在窗口外阻断\n'

# 每个输入断言都必须有坏样本；否则绿灯可能只是在查错字段。
for bad_case in \
  "19:" "31:" \
  "20:duplicate-target" "20:duplicate-reference" "20:unknown-target" \
  "20:invalid-time" "20:future-time" "20:extra-field" \
  "20:dangerous-reference" "20:empty-reference" \
  "20:wrong-contract" "20:extra-root"; do
  seed_plan "${bad_case%%:*}" "${bad_case#*:}"
  run
  expect_blocked_at "20–30 条窗口执行计划"
done

seed_plan 20
seed_array="$(jq -c '.seeds' "${SEED_PLAN}")"
printf '{"contractVersion":"evil","contractVersion":"t7-recording-window-plan.v1","seeds":%s}' \
  "${seed_array}" > "${SEED_PLAN}"
run
expect_blocked_at "20–30 条窗口执行计划"

seed_plan 20
printf '\n{"ignored":true}' >> "${SEED_PLAN}"
run
expect_blocked_at "20–30 条窗口执行计划"
printf 'ok  计划快照的重复键与尾随根值均在 jq 读取前阻断\n'

printf '{not-json' > "${SEED_PLAN}"
run
expect_blocked_at "20–30 条窗口执行计划"

seed_plan 20
printf '%132000s' '' >> "${SEED_PLAN}"
run
expect_blocked_at "20–30 条窗口执行计划"
grep -Fq "超过 128 KiB" "${OUT_FILE}" \
  || fail "oversized plan must fail before JSON interpretation"
printf 'ok  数量、唯一性、批次成员、历史时间、白名单、JSON 与 128 KiB 均 fail closed\n'

# ── ready path：只引用 server-owned targetId ───────────────────────
seed_plan 20
run
expect_ready "可以约窗口"
grep -Fq "全 workspace 返回 20 个可执行目标，分布在 2 个 system/service scope" "${OUT_FILE}" \
  || fail "10+10 across two services must satisfy one workspace denominator"
grep -Fq "20 条窗口执行项已冻结" "${OUT_FILE}" \
  || fail "passing output must report the selected batch size"
grep -Fq "workspace 批次 SHA-256" "${OUT_FILE}" \
  || fail "passing output must freeze the workspace batch"
grep -Fq "计划 SHA-256" "${OUT_FILE}" \
  || fail "passing output must freeze the exact plan snapshot"
grep -Fq "尚未验收" "${OUT_FILE}" || fail "NOT_ACCEPTED must be the window's job"
grep -Fq "真源采样仍然关着" "${OUT_FILE}" \
  || fail "sampling must remain closed before acceptance"
grep -Fq '"measurementAndFieldsVerified": false' "${OUT_FILE}" \
  || fail "owner checklist template must ship all-false"
if sed -n '/checklist/,/}/p' "${OUT_FILE}" | grep -Fq 'true'; then
  fail "no checklist item may be pre-filled true"
fi
printf 'ok  service-a 10 + service-b 10 → workspace 20，ready 且清单全 false\n'

recording_batch 30
state "$(signals log_search log_trace_bundle contrast_sample)" "fp-1" "NOT_ACCEPTED"
seed_plan 30
run
expect_ready "30 条窗口执行项已冻结"
printf 'ok  20–30 条批次边界双向通过\n'

# ── TOCTOU：替换原文件不能改变已验证/已哈希快照 ───────────────────
seed_plan 20
printf '%100000s' '' >> "${SEED_PLAN}"
original_hash="$(hash_file "${SEED_PLAN}")"
original_bytes="$(wc -c < "${SEED_PLAN}" | tr -d '[:space:]')"
printf '{"contractVersion":"replaced-after-snapshot","seeds":[]}' \
  > "${REPLACEMENT_PLAN}"

set +e
TMPDIR="${TOCTOU_TMPDIR}" \
  MATECLAW_BASE_URL="http://127.0.0.1:${PORT}" MATECLAW_TOKEN=stub-token \
  T7_SEED_PLAN_FILE="${SEED_PLAN}" \
  "${PREFLIGHT}" > "${OUT_FILE}" 2>&1 &
toctou_pid=$!
set -e

# 轮询要跟着预检进程的生命周期走，不能按固定次数封顶：10000 次 glob 在快机器上
# 不到一秒就跑完，而预检那时还在做 HTTP，快照根本还没建——于是这道 TOCTOU 守卫
# 会在越快的机器上越必然地误报。守卫本身是对的，挂错的是终止条件。
snapshot_seen=false
poll_deadline=$(( SECONDS + 120 ))
while kill -0 "${toctou_pid}" 2>/dev/null && (( SECONDS < poll_deadline )); do
  for snapshot in "${TOCTOU_TMPDIR}"/mateclaw-t7-preflight.*/plan.json; do
    [[ -f "${snapshot}" ]] || continue
    snapshot_bytes="$(wc -c < "${snapshot}" | tr -d '[:space:]')"
    if [[ "${snapshot_bytes}" == "${original_bytes}" ]]; then
      if stat -f '%Lp' "${snapshot}" >/dev/null 2>&1; then
        snapshot_mode="$(stat -f '%Lp' "${snapshot}")"
      else
        snapshot_mode="$(stat -c '%a' "${snapshot}")"
      fi
      [[ "${snapshot_mode}" == "600" ]] \
        || fail "plan snapshot mode must be 600, got ${snapshot_mode}"
      mv "${REPLACEMENT_PLAN}" "${SEED_PLAN}"
      snapshot_seen=true
      break 2
    fi
  done
done
[[ "${snapshot_seen}" == "true" ]] \
  || fail "did not observe the immutable plan snapshot before preflight completed.
预检当时的输出（不打出来就只能靠猜，而猜的尽头通常是把断言删掉）：
$(cat "${OUT_FILE}")"

set +e
wait "${toctou_pid}"
RC=$?
set -e
expect_ready "20 条窗口执行项已冻结"
grep -Fq "计划 SHA-256：${original_hash}" "${OUT_FILE}" \
  || fail "validated SHA must cover the snapshot, not the replaced source file"
printf 'ok  原计划校验途中被原子替换 → mode-600 快照的原 SHA 仍是唯一结果\n'

# ── owner acceptance 状态双向 ──────────────────────────────────────
seed_plan 30
state "$(signals log_search log_trace_bundle contrast_sample)" "fp-2" "ACCEPTED" "fp-1"
run
expect_ready "可以约窗口"
grep -Fq "按 STALE 处理" "${OUT_FILE}" \
  || fail "moved acceptance fingerprint must not read as completed"
printf 'ok  指纹漂移的 ACCEPTED → 按 STALE 处理\n'

state "$(signals log_search log_trace_bundle contrast_sample)" "fp-1" "ACCEPTED" "fp-1"
run
expect_ready "下一步按冻结清单采集 30 条"
grep -Fq "真源采样已开放" "${OUT_FILE}" || fail "acceptance must open sampling"
printf 'ok  指纹匹配的 ACCEPTED → 报已完成并进入批量采集\n'

# SHA 工具是冻结合同的一部分；缺失时必须是脚本错误（2），不能降级成无指纹 ready。
for tool in bash curl jq head mktemp python3 wc; do
  ln -s "$(command -v "${tool}")" "${NO_HASH_PATH}/${tool}"
done
set +e
PATH="${NO_HASH_PATH}" "${PREFLIGHT}" > "${OUT_FILE}" 2>&1
RC=$?
set -e
[[ "${RC}" -eq 2 ]] || fail "missing SHA tools must exit 2, got ${RC}"
grep -Fq "缺少 shasum / sha256sum" "${OUT_FILE}" \
  || fail "missing SHA tools must name the recovery dependency"
printf 'ok  缺 SHA-256 工具 → 脚本错误，不发布无指纹绿灯\n'

printf 'PASS: T7 preflight rejects false-green plans and exercises the real ready path\n'
