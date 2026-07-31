#!/usr/bin/env bash

# This launcher loads credentials. Refuse caller-provided xtrace so `bash -x`
# cannot echo dotenv assignments or the validated Agent ID to stderr.
if [[ "$-" == *x* ]]; then
  set +x
fi

set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
local_env_file="${MATECLAW_TROUBLESHOOTING_ENV_FILE:-${repo_root}/.env.guance.local}"

if [[ -f "${local_env_file}" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "${local_env_file}"
  set +a
fi

agent_enabled="${MATECLAW_TROUBLESHOOTING_AGENT_ENABLED:-false}"
agent_id="${MATECLAW_TROUBLESHOOTING_AGENT_ID:-0}"
max_iterations="${MATECLAW_TROUBLESHOOTING_AGENT_MAX_ITERATIONS:-6}"
max_evidence_requests="${MATECLAW_TROUBLESHOOTING_AGENT_MAX_EVIDENCE_REQUESTS:-6}"
max_prompt_chars="${MATECLAW_TROUBLESHOOTING_AGENT_MAX_PROMPT_CHARS:-32000}"

require_positive_integer() {
  local label="$1"
  local value="$2"
  local minimum="$3"
  if [[ ! "${value}" =~ ^[0-9]+$ ]] || (( value < minimum )); then
    echo "Invalid ${label}; expected an integer >= ${minimum}." >&2
    return 1
  fi
}

case "${agent_enabled}" in
  true|false) ;;
  *)
    echo "Invalid MATECLAW_TROUBLESHOOTING_AGENT_ENABLED; expected true or false." >&2
    exit 2
    ;;
esac

if [[ "${agent_enabled}" == "true" ]]; then
  require_positive_integer "MATECLAW_TROUBLESHOOTING_AGENT_ID" "${agent_id}" 1
  require_positive_integer \
    "MATECLAW_TROUBLESHOOTING_AGENT_MAX_ITERATIONS" "${max_iterations}" 1
  require_positive_integer \
    "MATECLAW_TROUBLESHOOTING_AGENT_MAX_EVIDENCE_REQUESTS" \
    "${max_evidence_requests}" 3
  require_positive_integer \
    "MATECLAW_TROUBLESHOOTING_AGENT_MAX_PROMPT_CHARS" "${max_prompt_chars}" 4096
fi

case "${1:-}" in
  --check)
    echo "Troubleshooting development configuration check passed."
    exit 0
    ;;
  "") ;;
  *)
    echo "Usage: scripts/run-troubleshooting-dev.sh [--check]" >&2
    exit 2
    ;;
esac

if [[ -z "${JAVA_HOME:-}" ]] && [[ -x /usr/libexec/java_home ]]; then
  export JAVA_HOME
  JAVA_HOME="$(/usr/libexec/java_home -v 21)"
fi

cd "${repo_root}"
exec mvn -pl mateclaw-server -DskipTests spring-boot:run
