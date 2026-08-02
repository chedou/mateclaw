#!/usr/bin/env bash
#
# 预检自己的回归。
#
# Why. 在本机跑，`troubleshooting-t7-preflight.sh` 只可能停在第 2 格——因为这里
# 本来就没接真源。也就是说它那条「就绪」路径**从来没有被走过**。一个只会说"没
# 就绪"的预检，和一个只会说"就绪"的一样没用：真正进内网那天，没人知道它会不会
# 在第 4 格上因为一个字段名写错而误报通过。
#
# 这支脚本用一个本地桩服务喂进各种真源状态，把 2→6 格逐个走通，
# 确认每一格既能拦住该拦的，也能放过该放的。
#
#   ./scripts/ci/test-troubleshooting-t7-preflight.sh
#
# Exit codes: 0 = 预检行为符合预期；1 = 不符合；2 = 跑不起来。

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PREFLIGHT="${ROOT_DIR}/scripts/troubleshooting-t7-preflight.sh"
PORT="${T7_STUB_PORT:-18099}"
STATE_FILE="$(mktemp -t t7-stub-state.XXXXXX)"
STUB_LOG="$(mktemp -t t7-stub-log.XXXXXX)"
OUT_FILE="$(mktemp -t t7-preflight-out.XXXXXX)"
STUB_PID=""

cleanup() {
  [[ -n "${STUB_PID}" ]] && kill "${STUB_PID}" 2>/dev/null || true
  rm -f "${STATE_FILE}" "${STUB_LOG}" "${OUT_FILE}"
}
trap cleanup EXIT

fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }

for tool in curl jq python3; do
  command -v "${tool}" >/dev/null 2>&1 || { printf 'missing %s\n' "${tool}" >&2; exit 2; }
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
        else:
            self.send_error(404)

HTTPServer(("127.0.0.1", int(os.environ["PORT"])), Handler).serve_forever()
PY

PORT="${PORT}" python3 "${STUB_LOG}.py" "${STATE_FILE}" &
STUB_PID=$!

# The stub must answer before any case runs, or a slow start would look like a
# preflight failure and this harness would be testing the wrong thing.
printf '{"readiness":{},"acceptance":{}}' > "${STATE_FILE}"
for _ in $(seq 1 50); do
  curl -sS -o /dev/null --max-time 1 \
    -X POST "http://127.0.0.1:${PORT}/api/v1/auth/login" 2>/dev/null && break
  sleep 0.2
done

signals() { # routed-core-signals...
  local routed=("$@") out="[]"
  for kind in log_search log_trace_bundle contrast_sample; do
    local is_routed=false status='"NOT_ROUTED"'
    for r in "${routed[@]}"; do
      [[ "${r}" == "${kind}" ]] && { is_routed=true; status='"READY_FOR_VALIDATION"'; }
    done
    out="$(jq -c --arg k "${kind}" --argjson r "${is_routed}" --argjson s "${status}" \
      '. + [{signalKind:$k, routedToGuance:$r, status:$s, bindingRef:"b", lastObservedAt:null, detail:""}]' \
      <<<"${out}")"
  done
  echo "${out}"
}

state() { # signals-json fingerprint acceptStatus acceptedFingerprint
  local sig="$1" fp="$2" st="$3" accepted_fp="${4:-}"
  local acceptance
  acceptance="$(jq -n --arg st "${st}" --arg fp "${fp}" --arg afp "${accepted_fp}" \
    '{status:$st,
      system:"CSDP", service:"csdp-session-service",
      currentBindingFingerprint: (if $fp == "" then null else $fp end),
      acceptance: (if $afp == "" then null else {bindingFingerprint:$afp} end),
      blockers: []}')"
  jq -n --argjson sig "${sig}" --argjson acc "${acceptance}" \
    '{readiness:{system:"CSDP", service:"csdp-session-service",
                 status:"READY_FOR_VALIDATION", adapterEnabled:true,
                 endpointConfigured:true, credentialState:"CONFIGURED",
                 uniqueAssetAuthorized:true, signals:$sig, blockers:[]},
      acceptance:$acc}' > "${STATE_FILE}"
}

run() { # -> exit code in RC, output in OUT_FILE
  set +e
  MATECLAW_BASE_URL="http://127.0.0.1:${PORT}" MATECLAW_TOKEN=stub-token \
    "${PREFLIGHT}" > "${OUT_FILE}" 2>&1
  RC=$?
  set -e
}

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

# ── adapter 关着必须停在第 2 格 ─────────────────────────────────────
jq -n '{readiness:{status:"DISABLED", adapterEnabled:false,
                   endpointConfigured:false, credentialState:"NOT_INSPECTED",
                   signals:[], blockers:["Guance adapter is disabled"]},
        acceptance:{status:"BLOCKED", currentBindingFingerprint:null,
                    acceptance:null, blockers:[]}}' > "${STATE_FILE}"
run
expect_blocked_at "Guance adapter 已启用"
grep -Fq "Guance adapter is disabled" "${OUT_FILE}" \
  || fail "the server's own blocker text must be shown verbatim"
printf 'ok  adapter 关着 → 停在第 2 格，并原样打出服务端的 blocker\n'

# ── 少一个核心 signal 必须停在第 3 格 ───────────────────────────────
state "$(signals log_search log_trace_bundle)" "fp-1" "NOT_ACCEPTED"
run
expect_blocked_at "三个核心 signal 已路由"
grep -Fq "contrast_sample" "${OUT_FILE}" \
  || fail "the missing signal must be named"
printf 'ok  少 contrast_sample → 停在第 3 格并点名\n'

# ── 指纹算不出必须停在第 4 格 ───────────────────────────────────────
state "$(signals log_search log_trace_bundle contrast_sample)" "" "BLOCKED"
run
expect_blocked_at "binding 指纹可唯一计算"
printf 'ok  指纹为 null → 停在第 4 格\n'

# ── 全部就位但未验收：通过，并给出验收模板 ──────────────────────────
state "$(signals log_search log_trace_bundle contrast_sample)" "fp-1" "NOT_ACCEPTED"
run
expect_ready "可以约窗口"
grep -Fq "尚未验收" "${OUT_FILE}" || fail "NOT_ACCEPTED must be reported as the window's job"
grep -Fq "真源采样仍然关着" "${OUT_FILE}" \
  || fail "sampling must be reported as correctly closed before acceptance"
# The template is an attestation, not a form to autofill.
grep -Fq '"measurementAndFieldsVerified": false' "${OUT_FILE}" \
  || fail "the checklist template must ship all-false"
# Explicit if-form: `grep ... && fail` relies on a set -e corner case, and a
# subtlety here would silently disarm the assertion.
if sed -n '/checklist/,/}/p' "${OUT_FILE}" | grep -Fq 'true'; then
  fail "no checklist item may be pre-filled true — that is signing for the owner"
fi
printf 'ok  配置就位未验收 → 通过，模板七项全 false\n'

# ── 验收指纹与当前指纹不一致：按 STALE 处理，不得报已完成 ───────────
state "$(signals log_search log_trace_bundle contrast_sample)" "fp-2" "ACCEPTED" "fp-1"
run
expect_ready "可以约窗口"
grep -Fq "按 STALE 处理" "${OUT_FILE}" \
  || fail "an acceptance whose fingerprint moved must not read as completed"
printf 'ok  指纹漂移的 ACCEPTED → 按 STALE 处理，不冒充已完成\n'

# ── 真正已验收：报已完成，并说下一步是攒样本 ────────────────────────
state "$(signals log_search log_trace_bundle contrast_sample)" "fp-1" "ACCEPTED" "fp-1"
run
expect_ready "下一步是攒样本"
grep -Fq "真源采样已开放" "${OUT_FILE}" || fail "acceptance must open sampling"
printf 'ok  指纹匹配的 ACCEPTED → 报已完成，下一步是攒样本\n'

printf 'PASS: T7 preflight behaves correctly on both the blocked and the ready paths\n'
