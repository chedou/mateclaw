#!/usr/bin/env bash
#
# Plan or apply the append-only MateClaw history merge. The password is read
# only from MATECLAW_HISTORY_DB_PASSWORD and is never passed on a command line.
#
# Usage:
#   export MATECLAW_HISTORY_DB_PASSWORD='...'
#   ./scripts/import-mysql-history.sh plan
#   MATECLAW_HISTORY_ALLOW_APPLY=true ./scripts/import-mysql-history.sh apply
#
# Optional overrides:
#   MATECLAW_HISTORY_DB_HOST=200.200.4.167
#   MATECLAW_HISTORY_DB_PORT=3306
#   MATECLAW_HISTORY_DB_USER=root
#   MATECLAW_HISTORY_SOURCE_SCHEMA=mateclaw_local
#   MATECLAW_HISTORY_TARGET_SCHEMA=mateclaw_sit
#   MATECLAW_HISTORY_BACKUP_DIR=/safe/backup/path
#   MATECLAW_MYSQL_CONNECTOR_JAR=/path/to/mysql-connector-j.jar
set -euo pipefail

cd "$(dirname "$0")/.."

MODE="${1:-plan}"
DB_HOST="${MATECLAW_HISTORY_DB_HOST:-200.200.4.167}"
DB_PORT="${MATECLAW_HISTORY_DB_PORT:-3306}"
DB_USER="${MATECLAW_HISTORY_DB_USER:-root}"
SOURCE_SCHEMA="${MATECLAW_HISTORY_SOURCE_SCHEMA:-mateclaw_local}"
TARGET_SCHEMA="${MATECLAW_HISTORY_TARGET_SCHEMA:-mateclaw_sit}"
BACKUP_DIR="${MATECLAW_HISTORY_BACKUP_DIR:-backups}"
PASSWORD="${MATECLAW_HISTORY_DB_PASSWORD:-}"
ALLOW_APPLY="${MATECLAW_HISTORY_ALLOW_APPLY:-false}"

log()  { printf '\033[36m[history]\033[0m %s\n' "$*"; }
warn() { printf '\033[33m[warn]\033[0m %s\n' "$*"; }
die()  { printf '\033[31m[error]\033[0m %s\n' "$*" >&2; exit 1; }

case "$MODE" in
    plan|apply) ;;
    *) die "usage: $0 <plan|apply>" ;;
esac
case "$DB_PORT" in
    ''|*[!0-9]*) die "database port must be numeric" ;;
esac
case "$SOURCE_SCHEMA" in
    ''|*[!A-Za-z0-9_]*) die "invalid source schema" ;;
esac
case "$TARGET_SCHEMA" in
    ''|*[!A-Za-z0-9_]*) die "invalid target schema" ;;
esac
[ "$SOURCE_SCHEMA" != "$TARGET_SCHEMA" ] || die "source and target schemas must differ"
[ -n "$PASSWORD" ] || die "MATECLAW_HISTORY_DB_PASSWORD is empty"
if [ "$MODE" = apply ] && [ "$ALLOW_APPLY" != true ]; then
    die "apply is locked; review plan, then set MATECLAW_HISTORY_ALLOW_APPLY=true"
fi

for command_name in java mysql mysqldump shasum mktemp; do
    command -v "$command_name" >/dev/null 2>&1 || die "$command_name is required"
done

CONNECTOR_JAR="${MATECLAW_MYSQL_CONNECTOR_JAR:-}"
if [ -z "$CONNECTOR_JAR" ]; then
    CONNECTOR_JAR="$(find "${M2_REPOSITORY:-$HOME/.m2/repository}/com/mysql/mysql-connector-j" \
        -type f -name 'mysql-connector-j-*.jar' \
        ! -name '*-sources.jar' ! -name '*-javadoc.jar' 2>/dev/null \
        | sort -V | tail -n 1)"
fi
[ -f "$CONNECTOR_JAR" ] || die "MySQL Connector/J was not found; set MATECLAW_MYSQL_CONNECTOR_JAR"

CLIENT_CONFIG="$(mktemp "${TMPDIR:-/tmp}/mateclaw-history-mysql.XXXXXX")"
cleanup() {
    rm -f "$CLIENT_CONFIG"
}
trap cleanup EXIT INT TERM
chmod 600 "$CLIENT_CONFIG"

escape_option_value() {
    local value="$1"
    value="${value//\\/\\\\}"
    value="${value//\"/\\\"}"
    printf '%s' "$value"
}

{
    printf '[client]\n'
    printf 'host="%s"\n' "$(escape_option_value "$DB_HOST")"
    printf 'port=%s\n' "$DB_PORT"
    printf 'user="%s"\n' "$(escape_option_value "$DB_USER")"
    printf 'password="%s"\n' "$(escape_option_value "$PASSWORD")"
    printf 'default-character-set=utf8mb4\n'
} > "$CLIENT_CONFIG"

log "checking MySQL TCP and credentials without exposing the password"
mysql --defaults-extra-file="$CLIENT_CONFIG" --connect-timeout=5 \
    --batch --skip-column-names -e 'SELECT 1' "$TARGET_SCHEMA" \
    | grep -Fxq '1' || die "cannot query target MySQL schema"

JDBC_URL="jdbc:mysql://${DB_HOST}:${DB_PORT}/${TARGET_SCHEMA}?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia%2FShanghai&useSSL=false&allowPublicKeyRetrieval=true"

run_merge() {
    local requested_mode="$1"
    MATECLAW_HISTORY_DB_PASSWORD="$PASSWORD" \
    MATECLAW_HISTORY_ALLOW_APPLY="${MATECLAW_HISTORY_ALLOW_APPLY:-false}" \
        java -cp "$CONNECTOR_JAR" scripts/mysql-history-merge.java \
        "$requested_mode" "$JDBC_URL" "$DB_USER" "$SOURCE_SCHEMA" "$TARGET_SCHEMA"
}

log "running fail-closed merge plan"
run_merge plan

if [ "$MODE" = plan ]; then
    exit 0
fi

mkdir -p "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR" 2>/dev/null || true
umask 077
BACKUP_FILE="$(mktemp "$BACKUP_DIR/${TARGET_SCHEMA}-pre-history-import-$(date '+%Y%m%d-%H%M%S').sql.XXXXXX")" \
    || die "cannot reserve a unique backup file"
log "creating consistent target backup: $BACKUP_FILE"
mysqldump --defaults-extra-file="$CLIENT_CONFIG" \
    --single-transaction --quick --routines --events --triggers --hex-blob \
    --set-gtid-purged=OFF --no-tablespaces "$TARGET_SCHEMA" > "$BACKUP_FILE"
[ -s "$BACKUP_FILE" ] || die "backup is empty"
chmod 600 "$BACKUP_FILE"
BACKUP_SHA256="$(shasum -a 256 "$BACKUP_FILE" | awk '{print $1}')"
log "backup ready: bytes=$(wc -c < "$BACKUP_FILE" | tr -d ' ') sha256=$BACKUP_SHA256"

log "re-running checks inside the apply transaction"
run_merge apply

log "running the idempotence plan; expected inserts=0"
IDEMPOTENCE_OUTPUT="$(run_merge plan)"
printf '%s\n' "$IDEMPOTENCE_OUTPUT"
printf '%s\n' "$IDEMPOTENCE_OUTPUT" | grep -Fq 'inserts=0,' \
    || die "post-import idempotence check did not report inserts=0"

log "history import completed; rollback backup: $BACKUP_FILE"
