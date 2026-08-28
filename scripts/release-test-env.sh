#!/usr/bin/env bash
#
# Trigger the existing Jenkins test-environment release and wait until the
# deployed MateClaw instance is healthy.
#
# Required credentials are read from the environment and never written into
# the repository or placed directly in curl's command-line arguments:
#
#   export JENKINS_USER='your-user'
#   export JENKINS_API_TOKEN='your-api-token'
#   ./scripts/release-test-env.sh --allow-insecure-http
#
# Optional overrides:
#
#   JENKINS_URL=http://200.200.4.33:8080
#   JENKINS_JOB=mateclaw-troubleshooting-release
#   MATECLAW_TEST_URL=http://smartfix-sit.sangfor.com
#   MATECLAW_RUNTIME_BASE_IMAGE=itharbor.sangfor.com/ai-uat/mateclaw-playwright:v1.62.0-noble@sha256:<digest>
#
# Parameterized jobs can receive one or more explicit parameters:
#
#   ./scripts/release-test-env.sh --allow-insecure-http \
#     --parameter BRANCH=claude/intelligent-troubleshooting-design
#
# Unless overridden, this script requests ACTION=DEPLOY, sends the current
# branch, and pins EXPECTED_COMMIT to the local full HEAD. The Jenkins job
# remains responsible for checkout, image build, migration precheck,
# maintenance-window switch and container rollback on its deployment agent.
set -euo pipefail

cd "$(dirname "$0")/.."

JENKINS_URL="${JENKINS_URL:-http://200.200.4.33:8080}"
JENKINS_JOB="${JENKINS_JOB:-mateclaw-troubleshooting-release}"
MATECLAW_TEST_URL="${MATECLAW_TEST_URL:-http://smartfix-sit.sangfor.com}"
APPROVED_RUNTIME_BASE_IMAGE='itharbor.sangfor.com/ai-uat/mateclaw-playwright:v1.62.0-noble@sha256:0e5163ed3364179e474b849dbecfaa46a06e21212abe2c67873f706dc609b88e'
MATECLAW_RUNTIME_BASE_IMAGE="${MATECLAW_RUNTIME_BASE_IMAGE:-$APPROVED_RUNTIME_BASE_IMAGE}"
POLL_SECONDS="${JENKINS_POLL_SECONDS:-5}"
TIMEOUT_SECONDS="${JENKINS_RELEASE_TIMEOUT_SECONDS:-1800}"
WAIT_FOR_BUILD=true
VERIFY_SITE=true
ALLOW_INSECURE_HTTP=false
EXPECTED_COMMIT=""
PARAMETERS=()

log()  { printf '\033[36m[release]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[warn]\033[0m %s\n' "$*"; }
die()  { printf '\033[31m[error]\033[0m %s\n' "$*" >&2; exit 1; }

usage() {
    sed -n '2,/^set -euo pipefail/p' "$0" | sed '$d; s/^# *//'
    cat <<'EOF'

Options:
  --parameter NAME=VALUE  Pass an explicit Jenkins build parameter (repeatable)
  --expected-commit SHA   Require this Git commit after deployment (default: HEAD)
  --allow-insecure-http   Allow Basic auth over an explicitly trusted HTTP LAN
  --no-wait               Return after Jenkins accepts the queue item
  --no-verify             Skip the deployed-site health check
  --help                  Show this help
EOF
}

urlencode() {
    local value="$1" out="" char hex index
    for ((index = 0; index < ${#value}; index++)); do
        char="${value:index:1}"
        case "$char" in
            [a-zA-Z0-9.~_-]) out+="$char" ;;
            *)
                printf -v hex '%%%02X' "'$char"
                out+="$hex"
                ;;
        esac
    done
    printf '%s' "$out"
}

json_value() {
    local path="$1"
    python3 -c '
import json, sys
value = json.load(sys.stdin)
for part in sys.argv[1].split("."):
    if not part:
        continue
    value = value.get(part) if isinstance(value, dict) else None
    if value is None:
        break
if isinstance(value, bool):
    print("true" if value else "false")
elif value is not None:
    print(value)
' "$path"
}

has_parameter() {
    local expected_name="$1" parameter
    for parameter in "${PARAMETERS[@]-}"; do
        [ "${parameter%%=*}" = "$expected_name" ] && return 0
    done
    return 1
}

parameter_value() {
    local expected_name="$1" parameter
    for parameter in "${PARAMETERS[@]-}"; do
        if [ "${parameter%%=*}" = "$expected_name" ]; then
            printf '%s' "${parameter#*=}"
            return 0
        fi
    done
    return 1
}

cleanup() {
    [ -z "${AUTH_CONFIG:-}" ] || rm -f "$AUTH_CONFIG"
    [ -z "${HEADER_FILE:-}" ] || rm -f "$HEADER_FILE"
}
trap cleanup EXIT INT TERM

while [ $# -gt 0 ]; do
    case "$1" in
        --parameter)
            [ $# -ge 2 ] || die "--parameter needs NAME=VALUE"
            case "$2" in
                *=*)
                    parameter_name="${2%%=*}"
                    case "$parameter_name" in
                        ACTION|BRANCH|EXPECTED_COMMIT|MATECLAW_RUNTIME_BASE_IMAGE) ;;
                        *) die "unsupported Jenkins parameter '$parameter_name'" ;;
                    esac
                    ! has_parameter "$parameter_name" \
                        || die "duplicate Jenkins parameter '$parameter_name'"
                    PARAMETERS+=("$2")
                    ;;
                *) die "invalid parameter '$2'; expected NAME=VALUE" ;;
            esac
            shift 2
            ;;
        --no-wait) WAIT_FOR_BUILD=false; shift ;;
        --no-verify) VERIFY_SITE=false; shift ;;
        --expected-commit)
            [ $# -ge 2 ] || die "--expected-commit needs a Git SHA"
            EXPECTED_COMMIT="$2"
            shift 2
            ;;
        --allow-insecure-http) ALLOW_INSECURE_HTTP=true; shift ;;
        -h|--help) usage; exit 0 ;;
        *) die "unknown option '$1'; try --help" ;;
    esac
done

# Reject an unreviewed image before checking local tooling, credentials or any
# Jenkins endpoint. This keeps the immutable-image boundary deterministic even
# on the old deployment host where optional CLI dependencies may be absent.
runtime_base_image="$(parameter_value MATECLAW_RUNTIME_BASE_IMAGE || true)"
runtime_base_image="${runtime_base_image:-$MATECLAW_RUNTIME_BASE_IMAGE}"
if [ "$runtime_base_image" != "$APPROVED_RUNTIME_BASE_IMAGE" ]; then
    die "MATECLAW_RUNTIME_BASE_IMAGE must equal the reviewed immutable Sangfor Harbor Playwright reference"
fi

command -v curl >/dev/null 2>&1 || die "curl is required"
command -v python3 >/dev/null 2>&1 || die "python3 is required to read Jenkins JSON"
[ -n "${JENKINS_USER:-}" ] || die "JENKINS_USER is empty"
[ -n "${JENKINS_API_TOKEN:-}" ] || die "JENKINS_API_TOKEN is empty"

JENKINS_URL="${JENKINS_URL%/}"
MATECLAW_TEST_URL="${MATECLAW_TEST_URL%/}"
jenkins_scheme="$(printf '%s' "${JENKINS_URL%%:*}" | tr '[:upper:]' '[:lower:]')"
case "$jenkins_scheme" in
    https) ;;
    http)
        [ "$ALLOW_INSECURE_HTTP" = true ] \
            || die "refusing to send Jenkins credentials over HTTP; use HTTPS or explicitly pass --allow-insecure-http for a trusted LAN"
        ;;
    *) die "unsupported Jenkins URL scheme '$jenkins_scheme'; expected HTTPS or explicitly allowed HTTP" ;;
esac
if [ -z "$EXPECTED_COMMIT" ]; then
    command -v git >/dev/null 2>&1 || die "git is required to determine the expected release commit"
    EXPECTED_COMMIT="$(git rev-parse HEAD 2>/dev/null)" \
        || die "cannot determine HEAD; pass --expected-commit explicitly"
fi
EXPECTED_COMMIT="$(printf '%s' "$EXPECTED_COMMIT" | tr '[:upper:]' '[:lower:]')"
case "$EXPECTED_COMMIT" in
    *[!0-9a-f]*|'') die "expected commit must be a hexadecimal Git SHA" ;;
esac
[ "${#EXPECTED_COMMIT}" -eq 40 ] \
    || die "expected commit must be the complete 40-character Git SHA"
if ! has_parameter ACTION; then
    PARAMETERS+=("ACTION=DEPLOY")
fi
if ! has_parameter BRANCH; then
    current_branch="$(git symbolic-ref --quiet --short HEAD 2>/dev/null)" \
        || die "cannot determine the current branch; pass --parameter BRANCH=name"
    PARAMETERS+=("BRANCH=$current_branch")
fi
if ! has_parameter EXPECTED_COMMIT; then
    PARAMETERS+=("EXPECTED_COMMIT=$EXPECTED_COMMIT")
fi
if ! has_parameter MATECLAW_RUNTIME_BASE_IMAGE; then
    PARAMETERS+=("MATECLAW_RUNTIME_BASE_IMAGE=$runtime_base_image")
fi
requested_action="$(parameter_value ACTION)"
case "$requested_action" in
    DEPLOY|VERIFY_ONLY|UPGRADE_LIBSECCOMP) ;;
    *) die "ACTION must be DEPLOY, VERIFY_ONLY or UPGRADE_LIBSECCOMP" ;;
esac
if [ "$requested_action" = "VERIFY_ONLY" ]; then
    VERIFY_SITE=false
    log "VERIFY_ONLY requested: deployed-site verification is skipped because no cutover occurs"
fi
if [ "$requested_action" = "UPGRADE_LIBSECCOMP" ]; then
    VERIFY_SITE=false
    log "UPGRADE_LIBSECCOMP requested: deployed-site identity verification is skipped because no application cutover occurs"
fi
log "expected release commit: $EXPECTED_COMMIT"
JOB_PATH=""
IFS='/' read -r -a job_parts <<< "$JENKINS_JOB"
for job_part in "${job_parts[@]}"; do
    [ -n "$job_part" ] || continue
    JOB_PATH+="/job/$(urlencode "$job_part")"
done
[ -n "$JOB_PATH" ] || die "JENKINS_JOB is empty"
JOB_URL="${JENKINS_URL}${JOB_PATH}"

AUTH_CONFIG="$(mktemp "${TMPDIR:-/tmp}/mateclaw-jenkins-auth.XXXXXX")"
HEADER_FILE="$(mktemp "${TMPDIR:-/tmp}/mateclaw-jenkins-headers.XXXXXX")"
chmod 600 "$AUTH_CONFIG" "$HEADER_FILE"
printf 'user = "%s:%s"\n' "$JENKINS_USER" "$JENKINS_API_TOKEN" > "$AUTH_CONFIG"

jenkins_curl() {
    curl --config "$AUTH_CONFIG" --fail --silent --show-error "$@"
}

log "checking Jenkins job: $JOB_URL"
job_json="$(jenkins_curl --max-time 15 "$JOB_URL/api/json")" \
    || die "cannot read the Jenkins job; check URL, user, token and permissions"
buildable="$(printf '%s' "$job_json" | json_value buildable)"
[ "$buildable" = "true" ] || die "Jenkins job is not buildable"

crumb_header=()
if crumb_json="$(jenkins_curl --max-time 15 "$JENKINS_URL/crumbIssuer/api/json" 2>/dev/null)"; then
    crumb_field="$(printf '%s' "$crumb_json" | json_value crumbRequestField)"
    crumb_value="$(printf '%s' "$crumb_json" | json_value crumb)"
    if [ -n "$crumb_field" ] && [ -n "$crumb_value" ]; then
        crumb_header=(-H "$crumb_field: $crumb_value")
    fi
fi

trigger_path="build"
trigger_query="delay=0sec"
if [ ${#PARAMETERS[@]} -gt 0 ]; then
    trigger_path="buildWithParameters"
    for parameter in "${PARAMETERS[@]}"; do
        name="${parameter%%=*}"
        value="${parameter#*=}"
        trigger_query+="&$(urlencode "$name")=$(urlencode "$value")"
    done
fi

log "triggering Jenkins release"
: > "$HEADER_FILE"
jenkins_curl -X POST -D "$HEADER_FILE" -o /dev/null \
    "${crumb_header[@]}" "$JOB_URL/$trigger_path?$trigger_query" \
    || die "Jenkins rejected the release request"
queue_url="$(awk 'BEGIN{IGNORECASE=1} /^Location:/ {gsub(/\r/, ""); print $2}' "$HEADER_FILE" | tail -1)"
[ -n "$queue_url" ] || die "Jenkins accepted no queue location; inspect the job permissions and configuration"
log "queued: $queue_url"

[ "$WAIT_FOR_BUILD" = true ] || exit 0

deadline=$((SECONDS + TIMEOUT_SECONDS))
build_url=""
while [ "$SECONDS" -lt "$deadline" ]; do
    queue_json="$(jenkins_curl --max-time 15 "${queue_url%/}/api/json")" \
        || die "cannot read Jenkins queue item"
    cancelled="$(printf '%s' "$queue_json" | json_value cancelled)"
    [ "$cancelled" != "true" ] || die "Jenkins cancelled the queued release"
    build_url="$(printf '%s' "$queue_json" | json_value executable.url)"
    [ -z "$build_url" ] || break
    sleep "$POLL_SECONDS"
done
[ -n "$build_url" ] || die "timed out waiting for Jenkins to start the build"
log "started: $build_url"

result=""
while [ "$SECONDS" -lt "$deadline" ]; do
    build_json="$(jenkins_curl --max-time 15 "${build_url%/}/api/json")" \
        || die "cannot read Jenkins build status"
    building="$(printf '%s' "$build_json" | json_value building)"
    result="$(printf '%s' "$build_json" | json_value result)"
    [ "$building" = "true" ] || break
    sleep "$POLL_SECONDS"
done
[ -n "$result" ] || die "timed out waiting for Jenkins to finish the build"
[ "$result" = "SUCCESS" ] || die "Jenkins build finished with $result: $build_url"
log "Jenkins build succeeded: $build_url"

[ "$VERIFY_SITE" = true ] || exit 0
log "verifying deployed site: $MATECLAW_TEST_URL"
health_body="$(curl --fail --silent --show-error --max-time 15 \
    "$MATECLAW_TEST_URL/actuator/health")" \
    || die "deployment succeeded but the health endpoint is unavailable"
health_status="$(printf '%s' "$health_body" | json_value status)"
[ "$health_status" = "UP" ] \
    || die "deployment succeeded but health status is '${health_status:-unknown}'"
info_body="$(curl --fail --silent --show-error --max-time 15 \
    "$MATECLAW_TEST_URL/actuator/info")" \
    || die "health is UP but release identity is unavailable"
deployed_commit="$(printf '%s' "$info_body" | json_value release.commit)"
[ "$deployed_commit" = "$EXPECTED_COMMIT" ] \
    || die "deployed commit '${deployed_commit:-unknown}' does not match expected '$EXPECTED_COMMIT'"
curl --fail --silent --show-error --max-time 15 -o /dev/null \
    "$MATECLAW_TEST_URL/troubleshooting" \
    || die "health is UP but the troubleshooting page is unavailable"
log "test environment is ready: $MATECLAW_TEST_URL/troubleshooting"
