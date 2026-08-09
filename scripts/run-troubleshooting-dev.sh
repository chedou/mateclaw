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

java_major_version() {
  local java_bin="$1"
  local version
  local major
  if [[ ! -x "${java_bin}" ]]; then
    return 1
  fi
  if ! version="$("${java_bin}" -version 2>&1)"; then
    return 1
  fi
  version="$(printf '%s\n' "${version}" \
    | awk -F'"' '/^[[:space:]]*(openjdk|java) version "/ { print $2; exit }')"
  if [[ "${version}" == 1.* ]]; then
    major="${version#1.}"
  else
    major="${version}"
  fi
  major="${major%%.*}"
  printf '%s\n' "${major%%-*}"
}

if [[ -n "${JAVA_HOME:-}" ]]; then
  current_java_bin="${JAVA_HOME}/bin/java"
else
  current_java_bin="$(command -v java || true)"
fi
current_java_major="$(java_major_version "${current_java_bin}" || true)"
if [[ -z "${JAVA_HOME:-}" ]] && [[ -x /usr/libexec/java_home ]]; then
  detected_java_home="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
  if [[ "$(java_major_version "${detected_java_home}/bin/java" || true)" == "21" ]]; then
    export JAVA_HOME="${detected_java_home}"
    current_java_major="21"
  fi
fi
if [[ "${current_java_major}" != "21" ]] && [[ -x /usr/libexec/java_home ]]; then
  detected_java_home="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
  if [[ "$(java_major_version "${detected_java_home}/bin/java" || true)" == "21" ]]; then
    export JAVA_HOME="${detected_java_home}"
    current_java_major="21"
  fi
fi

if [[ "${current_java_major}" != "21" ]]; then
  echo "MateClaw requires Java 21; set JAVA_HOME to a JDK 21 installation." >&2
  exit 2
fi

# Maven and spring-boot:run do not consistently choose JAVA_HOME's binary when
# another JDK appears earlier on PATH. Keep the launcher JVM and the forked
# application JVM on the same supported JDK.
if [[ -n "${JAVA_HOME:-}" ]]; then
  export PATH="${JAVA_HOME}/bin:${PATH}"
fi

cd "${repo_root}"
exec mvn -pl mateclaw-server -DskipTests spring-boot:run
