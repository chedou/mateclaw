#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/../.."

JAVA_SCRIPT="scripts/mysql-history-merge.java"
RUNNER="scripts/import-mysql-history.sh"

fail() { printf '[history-safety] %s\n' "$*" >&2; exit 1; }
require_text() {
    grep -Fq -- "$2" "$1" || fail "$1 is missing required guard: $2"
}
reject_text() {
    if grep -Fiq -- "$2" "$1"; then
        fail "$1 contains forbidden SQL form: $2"
    fi
}

require_text "$JAVA_SCRIPT" 'MATECLAW_HISTORY_ALLOW_APPLY'
require_text "$JAVA_SCRIPT" 'private static final Set<String> ALLOWED_TABLES'
require_text "$JAVA_SCRIPT" 'if (!ALLOWED_TABLES.contains(table))'
require_text "$JAVA_SCRIPT" 'private static final int EXPECTED_SOURCE_VERSION = 221'
require_text "$JAVA_SCRIPT" 'mate_troubleshooting_knowledge_outbox'
require_text "$JAVA_SCRIPT" 'mate_troubleshooting_open_discovery_claim'
require_text "$JAVA_SCRIPT" 'mate_troubleshooting_intake_session'
require_text "$JAVA_SCRIPT" 'row.put("dedup_key", null)'
require_text "$JAVA_SCRIPT" 'row.put("source_intake_session_id", null)'
require_text "$JAVA_SCRIPT" 'redactColumns(row, "title", "last_message", "progress_ledger")'
require_text "$JAVA_SCRIPT" 'redactColumns(row, "content", "content_parts", "metadata")'
require_text "$JAVA_SCRIPT" 'redactColumns(row, "aggregate_json")'
require_text "$JAVA_SCRIPT" 'assertNoSecrets(table, rows)'
require_text "$JAVA_SCRIPT" 'assertValidJson(connection, table, rows)'
require_text "$JAVA_SCRIPT" 'CURRENT_TRIAGE_AGENT_NAME'
require_text "$JAVA_SCRIPT" 'workspace_id = ? AND name = ?'
require_text "$RUNNER" '--single-transaction'
require_text "$RUNNER" 'BACKUP_FILE="$(mktemp'
require_text "$RUNNER" 'chmod 600 "$BACKUP_FILE"'
require_text ".gitignore" '/backups/'

for forbidden in 'DELETE FROM' 'TRUNCATE TABLE' 'DROP TABLE' \
        'REPLACE INTO' 'INSERT IGNORE' 'ON DUPLICATE KEY UPDATE'; do
    reject_text "$JAVA_SCRIPT" "$forbidden"
done

java "$JAVA_SCRIPT" self-test | grep -Fq 'redaction self-test passed' \
    || fail "redaction self-test failed"

set +e
OUTPUT="$(java "$JAVA_SCRIPT" 2>&1)"
STATUS=$?
set -e
[ "$STATUS" -eq 2 ] || fail "usage gate exited $STATUS instead of 2"
printf '%s\n' "$OUTPUT" | grep -Fq 'password must be supplied via' \
    || fail "usage gate did not explain password handling"

printf '[history-safety] static guards and Java source-file compilation passed\n'
