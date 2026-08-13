#!/usr/bin/env bash
#
# Bring up a MateClaw test environment with docker compose.
#
#   ./scripts/deploy-test-env.sh up --db mysql --db-host 10.0.0.9 \
#                                   --base-url http://10.0.0.5:18080
#   ./scripts/deploy-test-env.sh up --base-url http://10.0.0.5:18080
#   ./scripts/deploy-test-env.sh status
#   ./scripts/deploy-test-env.sh logs [service]
#   ./scripts/deploy-test-env.sh down
#   ./scripts/deploy-test-env.sh reset
#   ./scripts/deploy-test-env.sh rotate-db-password        # postgres only
#
# Two database modes:
#
#   postgres (default) — the bundled PostgreSQL 16 container. Self-contained:
#     the script generates every secret and the database comes up with the
#     stack. `reset` drops the volume and you are back to a clean install.
#
#   mysql — an external MySQL server you already run. The script never invents
#     credentials here, because they have to match an account that already
#     exists; it only checks that you supplied them. `reset` deliberately
#     refuses to touch that server.
#
# The engine is recorded in .env as DB_ENGINE on first run, so later commands
# do not need the flag again and cannot accidentally target the wrong stack.
set -euo pipefail

cd "$(dirname "$0")/.."
readonly ROOT="$PWD"
readonly ENV_FILE="$ROOT/.env"
readonly ENV_EXAMPLE="$ROOT/.env.example"

# Published by docker-compose.yml as 18080:18088.
readonly HOST_PORT=18080
readonly HEALTH_TIMEOUT_SECONDS=300

DB_ENGINE=""
BASE_URL=""
DB_HOST_ARG=""

log()  { printf '\033[36m[deploy]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[warn]\033[0m %s\n' "$*"; }
die()  { printf '\033[31m[error]\033[0m %s\n' "$*" >&2; exit 1; }

env_value() {
    [ -f "$ENV_FILE" ] || return 0
    grep -E "^$1=" "$ENV_FILE" | head -1 | cut -d= -f2-
}

# Flag beats the recorded value beats the default, so an existing test
# environment keeps its engine unless someone says otherwise on purpose.
resolve_engine() {
    if [ -z "$DB_ENGINE" ]; then
        DB_ENGINE="$(env_value DB_ENGINE)"
    fi
    DB_ENGINE="${DB_ENGINE:-postgres}"
    case "$DB_ENGINE" in
        postgres|mysql) ;;
        *) die "unsupported --db '$DB_ENGINE' (expected postgres or mysql)" ;;
    esac
}

compose() {
    if [ "$DB_ENGINE" = "mysql" ]; then
        "${COMPOSE[@]}" -f docker-compose.yml \
                        -f docker-compose.mysql.yml \
                        -f docker-compose.test.yml "$@"
    else
        "${COMPOSE[@]}" -f docker-compose.yml \
                        -f docker-compose.test.yml \
                        -f docker-compose.pg-test.yml "$@"
    fi
}

resolve_compose() {
    if docker compose version >/dev/null 2>&1; then
        COMPOSE=(docker compose)
    elif command -v docker-compose >/dev/null 2>&1; then
        COMPOSE=(docker-compose)
    else
        die "docker compose not found. Install Docker Engine 20.10+ with the compose plugin."
    fi
}

preflight() {
    command -v docker >/dev/null 2>&1 || die "docker not found"
    docker info >/dev/null 2>&1 || die "the docker daemon is not reachable; start Docker and retry"
    command -v openssl >/dev/null 2>&1 || die "openssl not found (needed to generate secrets)"
    resolve_compose

    # The frontend stage is pinned to a 6 GB Node heap because Rollup was
    # getting OOM-killed below that, so a small builder fails during the image
    # build rather than at runtime.
    local total_kb=""
    if [ -r /proc/meminfo ]; then
        total_kb=$(awk '/MemTotal/ {print $2}' /proc/meminfo)
    elif command -v sysctl >/dev/null 2>&1; then
        total_kb=$(( $(sysctl -n hw.memsize) / 1024 ))
    fi
    if [ -n "$total_kb" ] && [ "$total_kb" -lt 8000000 ]; then
        warn "host has < 8 GB RAM; the frontend build stage asks for a 6 GB Node heap and may be OOM-killed."
        warn "Build the image on a larger machine and push it, or raise this host's memory."
    fi
}

# Alphanumeric on purpose: docker compose parses .env itself, where '#' starts
# a comment and quoting differs from the shell. A password that is safe in
# every one of those readers beats four extra bits of entropy.
secret() {
    openssl rand -base64 48 | tr -dc 'A-Za-z0-9' | cut -c1-"${1:-32}"
}

set_env_value() {
    local key="$1" value="$2" file="$3"
    if grep -qE "^${key}=" "$file"; then
        # Through a temp file so a failure cannot leave a half-written .env,
        # and via awk rather than sed -i, which differs on GNU and BSD.
        awk -v k="$key" -v v="$value" \
            'BEGIN{FS=OFS="="} $1==k {print k "=" v; next} {print}' \
            "$file" > "$file.tmp"
        mv "$file.tmp" "$file"
    else
        printf '%s=%s\n' "$key" "$value" >> "$file"
    fi
}

apply_base_url() {
    [ -n "$BASE_URL" ] || return 0
    set_env_value MATECLAW_PUBLIC_BASE_URL                    "$BASE_URL" "$ENV_FILE"
    set_env_value MATECLAW_TROUBLESHOOTING_WORKBENCH_BASE_URL "$BASE_URL" "$ENV_FILE"
    set_env_value MATECLAW_CORS_ALLOWED_ORIGINS               "$BASE_URL" "$ENV_FILE"
}

generate_env_file() {
    [ -f "$ENV_EXAMPLE" ] || die ".env.example is missing; cannot generate .env"
    log "creating .env from .env.example"
    cp "$ENV_EXAMPLE" "$ENV_FILE"
    chmod 600 "$ENV_FILE"

    set_env_value DB_ENGINE      "$DB_ENGINE"   "$ENV_FILE"
    set_env_value JWT_SECRET     "$(secret 48)" "$ENV_FILE"
    set_env_value SEARXNG_SECRET "$(secret 48)" "$ENV_FILE"
    # A test box is the sanctioned place for a browsable API doc; production DB
    # profiles keep it admin-only.
    set_env_value MATECLAW_OPENAPI_EXPOSE_UI true "$ENV_FILE"

    if [ "$DB_ENGINE" = "postgres" ]; then
        set_env_value DB_PASSWORD       "$(secret 32)" "$ENV_FILE"
        set_env_value DB_ADMIN_PASSWORD "$(secret 32)" "$ENV_FILE"
        log "generated database secrets for the bundled PostgreSQL"
    else
        set_env_value DB_PORT "${DB_PORT:-3306}" "$ENV_FILE"
        [ -n "$DB_HOST_ARG" ] && set_env_value DB_HOST "$DB_HOST_ARG" "$ENV_FILE"
        # Deliberately not generated: these must match an account that already
        # exists on your server. Inventing one here would only produce a
        # confident-looking .env that cannot log in.
        warn "MySQL mode: fill in DB_HOST / DB_USERNAME / DB_PASSWORD in .env before continuing."
    fi

    apply_base_url
    log "wrote $ENV_FILE (mode 600)"
}

ensure_env_file() {
    if [ -f "$ENV_FILE" ]; then
        log "reusing the existing .env (secrets left untouched)"
        local recorded
        recorded="$(env_value DB_ENGINE)"
        if [ -n "$recorded" ] && [ "$recorded" != "$DB_ENGINE" ]; then
            die "this .env was created for '$recorded' but you asked for '$DB_ENGINE'.
       Switching engines against the same .env mixes two sets of credentials.
       Use a separate checkout, or move .env aside and start clean."
        fi
        set_env_value DB_ENGINE "$DB_ENGINE" "$ENV_FILE"
        [ -n "$DB_HOST_ARG" ] && set_env_value DB_HOST "$DB_HOST_ARG" "$ENV_FILE"
        apply_base_url
        return
    fi
    generate_env_file
    [ "$DB_ENGINE" = "mysql" ] && die "stopped so you can fill in the MySQL credentials; rerun 'up' afterwards"
    return 0
}

require_mysql_settings() {
    [ "$DB_ENGINE" = "mysql" ] || return 0
    local host user password
    host="$(env_value DB_HOST)"
    user="$(env_value DB_USERNAME)"
    password="$(env_value DB_PASSWORD)"
    [ -n "$host" ] && [ "$host" != "localhost" ] \
        || die "DB_HOST is not set to your MySQL server (currently '${host:-empty}')"
    [ -n "$user" ] || die "DB_USERNAME is empty in .env"
    [ -n "$password" ] && [ "$password" != "change-me-strong-user-password" ] \
        || die "DB_PASSWORD is still the placeholder from .env.example"
    log "MySQL target: ${user}@${host}:$(env_value DB_PORT)/$(env_value DB_NAME)"
    verify_postgres_gate_removed
}

# docker-compose.mysql.yml drops the PostgreSQL health gate with the `!override`
# tag, added in Compose v2.24. Rather than assert a version number, check the
# merged model itself: an older Compose would leave the gate in place and the
# server would then wait forever on a container this deployment never starts.
verify_postgres_gate_removed() {
    local merged=""
    if ! merged="$(compose config 2>&1)"; then
        printf '%s\n' "$merged" >&2
        die "docker compose could not merge the MySQL overlay (see above).
       An 'unknown tag !override' error means Compose is older than v2.24 —
       upgrade the compose plugin, then retry."
    fi
    # Under the merged model the server must depend on searxng alone.
    if printf '%s' "$merged" \
        | awk '/^  mateclaw-server:/{s=1;next} /^  [a-z]/{s=0} s' \
        | awk '/^    depends_on:/{d=1;next} /^    [a-z]/{d=0} d' \
        | grep -q 'postgres'; then
        die "this Compose still gates mateclaw-server on postgres despite the !override tag.
       Upgrade to Compose v2.24 or newer; below that the MySQL overlay cannot
       remove the dependency and the stack would never start."
    fi
    log "verified: the merged model no longer waits on PostgreSQL"
}

wait_for_health() {
    log "waiting for the server to report healthy (up to ${HEALTH_TIMEOUT_SECONDS}s)"
    local waited=0
    while [ "$waited" -lt "$HEALTH_TIMEOUT_SECONDS" ]; do
        if curl -fsS -m 3 "http://127.0.0.1:${HOST_PORT}/actuator/health" 2>/dev/null | grep -q '"status":"UP"'; then
            log "server is UP"
            return 0
        fi
        # A container that already exited will never become healthy; say so now
        # rather than after the full timeout.
        if [ -n "$(compose ps --status exited --quiet mateclaw-server 2>/dev/null)" ]; then
            warn "the mateclaw-server container exited; last lines:"
            compose logs --tail 40 mateclaw-server || true
            return 1
        fi
        sleep 5
        waited=$((waited + 5))
    done
    warn "still not healthy after ${HEALTH_TIMEOUT_SECONDS}s; last lines:"
    compose logs --tail 40 mateclaw-server || true
    return 1
}

cmd_up() {
    preflight
    ensure_env_file
    require_mysql_settings
    log "database engine: $DB_ENGINE"
    log "building images (the first run pulls a multi-GB Playwright base image)"
    compose build
    compose up -d
    if wait_for_health; then
        printf '\n'
        log "test environment ready"
        printf '  UI / API : %s\n' "${BASE_URL:-http://localhost:${HOST_PORT}}"
        printf '  Health   : http://127.0.0.1:%s/actuator/health\n' "$HOST_PORT"
        printf '  Swagger  : http://127.0.0.1:%s/swagger-ui.html\n' "$HOST_PORT"
        if [ "$DB_ENGINE" = "postgres" ]; then
            printf '  psql     : psql -h 127.0.0.1 -p %s -U %s -d %s\n' \
                "${TEST_DB_HOST_PORT:-5432}" "$(env_value DB_USERNAME)" "$(env_value DB_NAME)"
        fi
        printf '\n'
        log "next: open the UI, then Settings -> Models to add an LLM provider key."
        log "LLM keys are not environment variables; they are stored in the database."
    else
        if [ "$DB_ENGINE" = "mysql" ]; then
            warn "if the log shows an access or unknown-database error, check that the"
            warn "account exists, can reach this host, and that the database is utf8mb4."
        fi
        die "the stack did not become healthy; see the logs above"
    fi
}

cmd_status() {
    resolve_compose
    compose ps
    printf '\n'
    curl -fsS -m 3 "http://127.0.0.1:${HOST_PORT}/actuator/health" 2>/dev/null \
        && printf '\n' \
        || warn "health endpoint not answering on port ${HOST_PORT}"
}

cmd_logs() {
    resolve_compose
    compose logs -f --tail 200 "${1:-mateclaw-server}"
}

cmd_down() {
    resolve_compose
    log "stopping containers (volumes and data are kept)"
    compose down
}

cmd_reset() {
    resolve_compose
    if [ "$DB_ENGINE" = "mysql" ]; then
        warn "MySQL mode: this removes the server_data volume (uploads, skills)."
        warn "It does NOT touch your MySQL server. To reset schema data, drop and"
        warn "recreate the database there yourself — this script will not do that"
        warn "to a server it does not own."
    else
        warn "This deletes the postgres_data and server_data volumes."
        warn "Everything is lost: users, workspaces, model provider keys, diagnoses."
    fi
    printf 'Type RESET to confirm: '
    local answer=""
    read -r answer
    [ "$answer" = "RESET" ] || die "aborted"
    compose down -v
    log "volumes removed."
}

# The supported fix for "I changed DB_PASSWORD and now it will not start".
# docker/postgres/init/10-app-role.sh only runs on an empty data directory, so
# the role keeps the password it was created with until someone alters it.
cmd_rotate_db_password() {
    resolve_compose
    [ "$DB_ENGINE" = "postgres" ] \
        || die "rotate-db-password only applies to the bundled PostgreSQL.
       Your MySQL server is not managed by this script — rotate the password
       there, then update DB_PASSWORD in .env and rerun 'up'."
    [ -f "$ENV_FILE" ] || die ".env not found; nothing to rotate"

    local admin_user db_name app_user new_password
    admin_user="$(env_value DB_ADMIN_USERNAME)"; admin_user="${admin_user:-mateclaw_admin}"
    db_name="$(env_value DB_NAME)";              db_name="${db_name:-mateclaw}"
    app_user="$(env_value DB_USERNAME)";         app_user="${app_user:-mateclaw}"
    new_password="$(secret 32)"

    log "rotating the password for role '$app_user'"
    # Passed as a psql variable and quoted by psql via %L, matching how
    # docker/postgres/init/10-app-role.sh does it.
    compose exec -T postgres psql -v ON_ERROR_STOP=1 \
        --username "$admin_user" --dbname "$db_name" \
        -v app_user="$app_user" -v app_pw="$new_password" <<'EOSQL'
        SELECT format('ALTER ROLE %I PASSWORD %L', :'app_user', :'app_pw')
        \gexec
EOSQL

    set_env_value DB_PASSWORD "$new_password" "$ENV_FILE"
    log "database and .env are back in sync; restarting the server"
    compose up -d --force-recreate mateclaw-server
    wait_for_health || die "server did not come back healthy after the rotation"
}

main() {
    local command="${1:-up}"
    [ $# -gt 0 ] && shift || true

    while [ $# -gt 0 ]; do
        case "$1" in
            --db)
                DB_ENGINE="${2:-}"
                [ -n "$DB_ENGINE" ] || die "--db needs a value: postgres or mysql"
                shift 2 ;;
            --db-host)
                DB_HOST_ARG="${2:-}"
                [ -n "$DB_HOST_ARG" ] || die "--db-host needs a value, e.g. 10.0.0.9"
                shift 2 ;;
            --base-url)
                BASE_URL="${2:-}"
                [ -n "$BASE_URL" ] || die "--base-url needs a value, e.g. http://10.0.0.5:18080"
                shift 2 ;;
            *) break ;;
        esac
    done

    resolve_engine

    case "$command" in
        up)                 cmd_up ;;
        status)             cmd_status ;;
        logs)               cmd_logs "${1:-}" ;;
        down)               cmd_down ;;
        reset)              cmd_reset ;;
        rotate-db-password) cmd_rotate_db_password ;;
        -h|--help|help)
            # Print the header comment block: from line 2 to the first line
            # that is no longer a comment. Content-driven so it cannot drift.
            awk 'NR>1 { if ($0 !~ /^#/) exit; sub(/^# ?/, ""); print }' "$0"
            ;;
        *) die "unknown command '$command'; try --help" ;;
    esac
}

main "$@"
