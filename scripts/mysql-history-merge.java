/// Safely appends user-visible MateClaw history from one MySQL schema into
/// another schema on the same server.
///
/// This is deliberately not a general database copier. It has a small allow
/// list of user-visible history tables and explicit Agent remapping. Any table
/// outside the three-table allow list is rejected. Existing target rows are
/// never updated or deleted.
///
/// Usage (Java 21 source-file mode):
///   MATECLAW_HISTORY_DB_PASSWORD=... \
///   java -cp mysql-connector-j.jar scripts/mysql-history-merge.java \
///        <plan|apply> <mysql-jdbc-url> <mysql-user> <source-schema> <target-schema>
///
/// `plan` is read-only. `apply` additionally requires:
///   MATECLAW_HISTORY_ALLOW_APPLY=true
///
/// The first production batch is intentionally limited to conversations,
/// messages, and diagnoses. Intake, follow-up runs, claims, evidence runs,
/// queues, and configuration remain outside this tool. The source must be at
/// Flyway V221 and the target at V223.

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class Main {

    private static final String PASSWORD_ENV = "MATECLAW_HISTORY_DB_PASSWORD";
    private static final String APPLY_ENV = "MATECLAW_HISTORY_ALLOW_APPLY";
    private static final String LOCK_NAME = "mateclaw:mysql-history-merge";
    private static final int EXPECTED_SOURCE_VERSION = 221;
    private static final int EXPECTED_TARGET_VERSION = 223;
    private static final int BATCH_SIZE = 250;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_]+");

    private static final long OLD_TRIAGE_AGENT = 2083128519379722242L;
    private static final long CURRENT_TRIAGE_AGENT = 1000000950L;
    private static final long OLD_SMARTFIX_AGENT = 2087578800647929857L;
    private static final String CURRENT_TRIAGE_AGENT_NAME = "troubleshooting-readonly-triage";
    private static final String REDACTED = "<REDACTED>";

    /** The only tables this source file can ever read for insertion. */
    private static final Set<String> ALLOWED_TABLES = Set.of(
            "mate_conversation",
            "mate_message",
            "mate_troubleshooting_diagnosis");
    private static final Map<String, List<String>> JSON_COLUMNS = Map.of(
            "mate_conversation", List.of("progress_ledger"),
            "mate_message", List.of("content_parts", "metadata"),
            "mate_troubleshooting_diagnosis", List.of("aggregate_json"));

    /** Protected tables are counted before and after apply as an extra invariant. */
    private static final Set<String> PROTECTED_TABLES = Set.of(
            "mate_user", "mate_workspace", "mate_agent", "mate_tool",
            "mate_provider", "mate_channel", "mate_troubleshooting_sop",
            "mate_troubleshooting_playbook_candidate",
            "mate_troubleshooting_playbook_version",
            "mate_troubleshooting_evidence_route",
            "mate_troubleshooting_evidence_settings",
            "mate_troubleshooting_observability_asset",
            "mate_troubleshooting_deployment_topology",
            "mate_troubleshooting_evidence_contract",
            "mate_troubleshooting_source_acceptance",
            "mate_troubleshooting_guance_acceptance",
            "mate_troubleshooting_pilot_plan",
            "mate_troubleshooting_pilot_enrollment",
            "mate_troubleshooting_open_discovery_agent_binding",
            "mate_troubleshooting_open_discovery_claim",
            "mate_troubleshooting_formal_diagnosis_claim",
            "mate_troubleshooting_knowledge_outbox",
            "mate_troubleshooting_intake_session",
            "mate_troubleshooting_intake_message",
            "mate_troubleshooting_intake_investigation",
            "mate_troubleshooting_scenario_evidence_run",
            "mate_troubleshooting_open_discovery_run",
            "mate_troubleshooting_topology_probe_run",
            "mate_troubleshooting_diagnosis_follow_up_run");

    private static final Pattern SECRET_KEY = Pattern.compile(
            "(?i)(?:proxy[-_ ]?authorization|authorization|"
                    + "api[-_ ]?key|access[-_ ]?key(?:[-_ ]?id)?|"
                    + "secret[-_ ]?(?:access[-_ ]?)?key|private[-_ ]?key|"
                    + "access[-_ ]?token|refresh[-_ ]?token|id[-_ ]?token|"
                    + "session[-_ ]?token|"
                    + "oauth[-_ ]?token|token|client[-_ ]?secret|password|credentials?|"
                    + "passwd|pwd|set[-_ ]?cookie|cookie|secret)(?![A-Za-z0-9])");
    private static final Pattern BEARER_SECRET = Pattern.compile(
            "(?i)((?:bearer|basic)\\s+)([^\\s,;\\\\\\\"'}]+)", Pattern.MULTILINE);
    private static final Pattern URL_SECRET_SIGNATURE = Pattern.compile(
            "(?i)(?:[?&]|\\\\+u0026)(?:token|access[-_]?token|refresh[-_]?token|"
                    + "id[-_]?token|session[-_]?token|oauth[-_]?token|api[-_]?key|"
                    + "password|passwd|pwd|client[-_]?secret)\\s*=\\s*"
                    + "(?!<REDACTED>)[A-Za-z0-9%._~+\\-/]{8,}");

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "self-test".equals(args[0])) {
            runRedactionSelfTest();
            return;
        }
        if (args.length != 5) {
            usage();
            System.exit(2);
        }
        boolean apply = switch (args[0]) {
            case "plan" -> false;
            case "apply" -> true;
            default -> {
                System.err.println("mode must be 'plan' or 'apply'");
                yield false;
            }
        };
        if (!"plan".equals(args[0]) && !"apply".equals(args[0])) {
            System.exit(2);
        }
        String jdbcUrl = args[1];
        String user = args[2];
        String sourceSchema = requireIdentifier(args[3], "source schema");
        String targetSchema = requireIdentifier(args[4], "target schema");
        if (sourceSchema.equals(targetSchema)) {
            fail("source and target schemas must differ");
        }
        if (jdbcUrl.toLowerCase(Locale.ROOT).contains("password=")) {
            fail("do not put a password in the JDBC URL; use " + PASSWORD_ENV);
        }
        String password = System.getenv(PASSWORD_ENV);
        if (password == null || password.isBlank()) {
            fail(PASSWORD_ENV + " is empty");
        }
        if (apply && !"true".equals(System.getenv(APPLY_ENV))) {
            fail("apply is locked; set " + APPLY_ENV + "=true after reviewing plan and backup");
        }

        try (Connection connection = DriverManager.getConnection(jdbcUrl, user, password)) {
            requireMysql(connection);
            String current = currentSchema(connection);
            if (!targetSchema.equals(current)) {
                fail("connected to schema '" + current + "', expected target '" + targetSchema + "'");
            }
            requireSchema(connection, sourceSchema);
            requireSchema(connection, targetSchema);
            acquireLock(connection);
            try {
                run(connection, apply, sourceSchema, targetSchema);
            } finally {
                releaseLock(connection);
            }
        }
    }

    private static void run(
            Connection connection,
            boolean apply,
            String sourceSchema,
            String targetSchema) throws Exception {
        exec(connection, "SET SESSION TRANSACTION ISOLATION LEVEL REPEATABLE READ");
        connection.setAutoCommit(false);
        try {
            exec(connection, "START TRANSACTION WITH CONSISTENT SNAPSHOT");
            int sourceVersion = validateFlyway(connection, sourceSchema, true);
            int targetVersion = validateFlyway(connection, targetSchema, false);
            System.out.printf("source=%s V%d, target=%s V%d%n",
                    sourceSchema, sourceVersion, targetSchema, targetVersion);

            Context context = loadContext(connection, sourceSchema, targetSchema, sourceVersion);
            Map<String, Long> beforeSafety = safetyMetrics(connection, targetSchema);
            Map<String, Long> protectedBefore = protectedCounts(connection, targetSchema);

            List<TablePlan> plans = new ArrayList<>();
            plans.add(planSourceTable(
                    connection,
                    sourceSchema,
                    targetSchema,
                    "mate_conversation",
                    "t.deleted = 0",
                    row -> transformConversation(row, context)));
            plans.add(planSourceTable(
                    connection,
                    sourceSchema,
                    targetSchema,
                    "mate_message",
                    "t.deleted = 0 AND EXISTS (SELECT 1 FROM "
                            + table(sourceSchema, "mate_conversation")
                            + " c WHERE c.conversation_id = t.conversation_id "
                            + "AND c.deleted = 0)",
                    row -> transformMessage(row, context)));

            plans.add(planSourceTable(
                    connection,
                    sourceSchema,
                    targetSchema,
                    "mate_troubleshooting_diagnosis",
                    "t.deleted = 0",
                    Main::transformDiagnosis));

            long totalSource = plans.stream().mapToLong(TablePlan::sourceRows).sum();
            long totalInsert = plans.stream().mapToLong(p -> p.inserts().size()).sum();
            long totalExact = plans.stream().mapToLong(TablePlan::exactRows).sum();
            System.out.printf("conversation namespace=%s; source tasks_1 messages preserved=%d%n",
                    context.conversationPrefix(), context.sourceTaskMessages());
            for (TablePlan plan : plans) {
                System.out.printf("  %-55s source=%d insert=%d exact=%d%n",
                        plan.table(), plan.sourceRows(), plan.inserts().size(), plan.exactRows());
            }
            System.out.printf("planned source rows=%d, inserts=%d, exact/idempotent=%d%n",
                    totalSource, totalInsert, totalExact);
            System.out.println("secret scan=0; no unredacted credential signature remains");

            if (!apply) {
                connection.rollback();
                System.out.println("plan complete; transaction rolled back; nothing written");
                return;
            }

            Map<String, Long> countsBefore = new LinkedHashMap<>();
            for (TablePlan plan : plans) {
                countsBefore.put(plan.table(), count(connection, targetSchema, plan.table()));
                insertRows(connection, targetSchema, plan);
            }

            validateInsertedCounts(connection, targetSchema, plans, countsBefore);
            requireNotIncreased(beforeSafety, safetyMetrics(connection, targetSchema));
            requireEqual("protected table counts", protectedBefore,
                    protectedCounts(connection, targetSchema));
            validateImportedConversationCounts(connection, targetSchema, plans.getFirst());

            connection.commit();
            System.out.printf("committed %d historical rows; no existing target row was changed%n",
                    totalInsert);
        } catch (Exception error) {
            connection.rollback();
            throw error;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private static Context loadContext(
            Connection connection,
            String sourceSchema,
            String targetSchema,
            int sourceVersion) throws SQLException {
        requireAgentMappings(connection, sourceSchema, targetSchema);

        Map<String, Integer> messageCounts = new HashMap<>();
        String countSql = "SELECT conversation_id, COUNT(*) FROM "
                + table(sourceSchema, "mate_message")
                + " WHERE deleted = 0 GROUP BY conversation_id";
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(countSql)) {
            while (rows.next()) {
                messageCounts.put(rows.getString(1), rows.getInt(2));
            }
        }
        long sourceTaskMessages = queryLong(connection,
                "SELECT COUNT(*) FROM " + table(sourceSchema, "mate_message")
                        + " WHERE deleted = 0 AND conversation_id = 'tasks_1'");
        String conversationPrefix = "legacy_local_v" + sourceVersion + "__";
        return new Context(
                messageCounts,
                sourceTaskMessages,
                conversationPrefix);
    }

    private static void requireAgentMappings(
            Connection connection,
            String sourceSchema,
            String targetSchema) throws SQLException {
        Map<AgentRef, String> required = new LinkedHashMap<>();
        String sql = "SELECT DISTINCT c.workspace_id, c.agent_id, a.name FROM "
                + table(sourceSchema, "mate_conversation") + " c LEFT JOIN "
                + table(sourceSchema, "mate_agent") + " a ON a.id = c.agent_id "
                + "AND a.workspace_id = c.workspace_id AND a.deleted = 0 "
                + "WHERE c.deleted = 0 AND c.agent_id IS NOT NULL";
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                long workspaceId = rows.getLong(1);
                long sourceAgentId = rows.getLong(2);
                String sourceName = rows.getString(3);
                if (sourceName == null || sourceName.isBlank()) {
                    throw new SQLException("source Agent " + sourceAgentId
                            + " is missing from workspace " + workspaceId);
                }
                long targetAgentId = mappedAgent(sourceAgentId);
                String targetName = isLegacyTroubleshootingAgent(sourceAgentId)
                        ? CURRENT_TRIAGE_AGENT_NAME
                        : sourceName;
                AgentRef key = new AgentRef(workspaceId, targetAgentId);
                String previous = required.putIfAbsent(key, targetName);
                if (previous != null && !previous.equals(targetName)) {
                    throw new SQLException("ambiguous Agent mapping for target "
                            + targetAgentId + " in workspace " + workspaceId);
                }
            }
        }
        for (Map.Entry<AgentRef, String> mapping : required.entrySet()) {
            AgentRef agent = mapping.getKey();
            long found = queryLong(connection,
                    "SELECT COUNT(*) FROM " + table(targetSchema, "mate_agent")
                            + " WHERE id = ? AND workspace_id = ? AND name = ? "
                            + "AND enabled = 1 AND deleted = 0",
                    agent.agentId(), agent.workspaceId(), mapping.getValue());
            if (found != 1) {
                throw new SQLException("target Agent " + agent.agentId()
                        + " required by historical conversations is not uniquely enabled as '"
                        + mapping.getValue() + "' in workspace " + agent.workspaceId());
            }
        }
    }

    private static Map<String, Object> transformConversation(
            Map<String, Object> source,
            Context context) {
        Map<String, Object> row = copy(source);
        Object value = row.get("agent_id");
        if (value instanceof Number number) {
            row.put("agent_id", mappedAgent(number.longValue()));
        }
        String sourceConversationId = Objects.toString(row.get("conversation_id"), "");
        row.put("conversation_id", historyConversationId(
                context.conversationPrefix(), sourceConversationId, 128));
        Object parent = row.get("parent_conversation_id");
        if (parent != null && !parent.toString().isBlank()) {
            row.put("parent_conversation_id", historyConversationId(
                    context.conversationPrefix(), parent.toString(), 64));
        }
        redactColumns(row, "title", "last_message", "progress_ledger");
        Object title = row.get("title");
        row.put("title", truncate("[本地历史] " + Objects.toString(title, "未命名会话"), 256));
        row.put("message_count", context.messageCounts().getOrDefault(sourceConversationId, 0));
        row.put("stream_status", "idle");
        row.put("webchat_session_id", null);
        return row;
    }

    private static Map<String, Object> transformMessage(
            Map<String, Object> source,
            Context context) {
        Map<String, Object> row = copy(source);
        row.put("conversation_id", historyConversationId(
                context.conversationPrefix(),
                Objects.toString(source.get("conversation_id"), ""),
                128));
        redactColumns(row, "content", "content_parts", "metadata");
        return row;
    }

    private static long mappedAgent(long sourceAgent) {
        if (isLegacyTroubleshootingAgent(sourceAgent)) {
            return CURRENT_TRIAGE_AGENT;
        }
        return sourceAgent;
    }

    private static boolean isLegacyTroubleshootingAgent(long sourceAgent) {
        return sourceAgent == OLD_TRIAGE_AGENT || sourceAgent == OLD_SMARTFIX_AGENT;
    }

    private static String historyConversationId(
            String prefix,
            String sourceId,
            int maxLength) {
        String direct = prefix + sourceId;
        if (direct.length() <= maxLength) {
            return direct;
        }
        String digest = sha256(sourceId).substring(0, 16);
        int visible = maxLength - prefix.length() - digest.length() - 2;
        if (visible < 1) {
            throw new IllegalArgumentException("history prefix is too long for conversation id");
        }
        return prefix + sourceId.substring(0, Math.min(visible, sourceId.length()))
                + "__" + digest;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static void redactColumns(Map<String, Object> row, String... columns) {
        for (String column : columns) {
            Object value = row.get(column);
            if (value == null) {
                continue;
            }
            if (!(value instanceof String text)) {
                throw new IllegalArgumentException("expected text in " + column
                        + ", got " + value.getClass().getSimpleName());
            }
            row.put(column, redactSecrets(text));
        }
    }

    /**
     * String-only copy of the application's deterministic troubleshooting
     * redaction algorithm. Keeping the importer self-contained lets it run
     * before the application starts while preserving JSON and escaped JSON.
     */
    private static String redactSecrets(String value) {
        String sanitized = redactNamedSecrets(value == null ? "" : value);
        sanitized = redactCredentialSchemes(sanitized);
        return redactJwtSecrets(sanitized);
    }

    private static String redactCredentialSchemes(String value) {
        Matcher matcher = BEARER_SECRET.matcher(value);
        StringBuffer output = null;
        while (matcher.find()) {
            if (!looksLikeCredential(matcher.group(1), matcher.group(2))) {
                continue;
            }
            if (output == null) {
                output = new StringBuffer(value.length());
            }
            matcher.appendReplacement(output,
                    Matcher.quoteReplacement(matcher.group(1) + REDACTED));
        }
        if (output == null) {
            return value;
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static boolean looksLikeCredential(String scheme, String candidate) {
        if (candidate.equals(REDACTED)) {
            return false;
        }
        if (scheme.regionMatches(true, 0, "basic", 0, 5)) {
            try {
                int padding = (4 - candidate.length() % 4) % 4;
                byte[] decoded = Base64.getDecoder().decode(candidate + "=".repeat(padding));
                return new String(decoded, StandardCharsets.UTF_8).contains(":");
            } catch (IllegalArgumentException invalidBase64) {
                return false;
            }
        }
        return candidate.length() >= 16;
    }

    private static String redactNamedSecrets(String value) {
        Matcher matcher = SECRET_KEY.matcher(value);
        StringBuilder output = null;
        int appendFrom = 0;
        int searchFrom = 0;
        while (matcher.find(searchFrom)) {
            if (!isSecretKeyBoundary(value, matcher.start())) {
                searchFrom = matcher.end();
                continue;
            }
            SecretSpan span = secretSpan(value, matcher.group(), matcher.end());
            if (span == null) {
                searchFrom = matcher.end();
                continue;
            }
            if (output == null) {
                output = new StringBuilder(value.length());
            }
            output.append(value, appendFrom, span.valueStart()).append(REDACTED);
            appendFrom = span.valueEnd();
            searchFrom = Math.max(span.valueEnd(), matcher.end());
        }
        if (output == null) {
            return value;
        }
        return output.append(value, appendFrom, value.length()).toString();
    }

    private static SecretSpan secretSpan(String value, String key, int keyEnd) {
        int cursor = skipWhitespace(value, keyEnd);
        cursor = skipOptionalQuote(value, cursor);
        cursor = skipWhitespace(value, cursor);
        if (cursor >= value.length()
                || (value.charAt(cursor) != ':' && value.charAt(cursor) != '=')) {
            return null;
        }
        cursor = skipWhitespace(value, cursor + 1);
        Quote quote = openingQuote(value, cursor);
        if (quote != null) {
            cursor += quote.width();
        }

        int valueStart = cursor;
        int schemeEnd = credentialSchemeEnd(value, valueStart);
        boolean knownCredentialScheme = schemeEnd > valueStart;
        if (knownCredentialScheme) {
            valueStart = skipWhitespace(value, schemeEnd);
        }

        int valueEnd;
        if (quote != null) {
            valueEnd = closingQuote(value, valueStart, quote);
        } else if (valueStart < value.length()
                && (value.charAt(valueStart) == '[' || value.charAt(valueStart) == '{')) {
            valueEnd = endOfContainer(value, valueStart);
        } else if (normalizeKey(key).equals("cookie")) {
            valueEnd = endOfLine(value, valueStart);
        } else if (normalizeKey(key).equals("authorization")) {
            valueEnd = knownCredentialScheme
                    ? endOfToken(value, valueStart)
                    : endOfLine(value, valueStart);
        } else {
            valueEnd = endOfUnquotedValue(value, valueStart);
        }
        return new SecretSpan(valueStart, Math.max(valueStart, valueEnd));
    }

    private static int skipOptionalQuote(String value, int cursor) {
        Quote quote = openingQuote(value, cursor);
        return quote == null ? cursor : cursor + quote.width();
    }

    private static Quote openingQuote(String value, int cursor) {
        if (cursor >= value.length()) {
            return null;
        }
        char current = value.charAt(cursor);
        if (current == '"' || current == '\'') {
            return new Quote(current, 0);
        }
        if (current != '\\') {
            return null;
        }
        int quoteIndex = cursor;
        while (quoteIndex < value.length() && value.charAt(quoteIndex) == '\\') {
            quoteIndex++;
        }
        if (quoteIndex < value.length()) {
            char encodedQuote = value.charAt(quoteIndex);
            if (encodedQuote == '"' || encodedQuote == '\'') {
                return new Quote(encodedQuote, quoteIndex - cursor);
            }
        }
        return null;
    }

    private static int closingQuote(String value, int cursor, Quote quote) {
        if (quote.escaped()) {
            int consecutiveBackslashes = 0;
            for (int index = cursor; index < value.length(); index++) {
                char current = value.charAt(index);
                if (current == '\\') {
                    consecutiveBackslashes++;
                    continue;
                }
                if (closesEncodedQuote(quote, current, consecutiveBackslashes)) {
                    return index - quote.backslashCount();
                }
                consecutiveBackslashes = 0;
            }
            return value.length();
        }
        boolean escaped = false;
        for (int index = cursor; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == quote.value() && !escaped) {
                return index;
            }
            if (current == '\\' && !escaped) {
                escaped = true;
            } else {
                escaped = false;
            }
        }
        return value.length();
    }

    private static boolean closesEncodedQuote(
            Quote quote,
            char current,
            int consecutiveBackslashes) {
        return current == quote.value()
                && consecutiveBackslashes == quote.backslashCount();
    }

    private static int credentialSchemeEnd(String value, int cursor) {
        for (String scheme : List.of("bearer", "basic")) {
            int end = cursor + scheme.length();
            if (end < value.length()
                    && value.regionMatches(true, cursor, scheme, 0, scheme.length())
                    && Character.isWhitespace(value.charAt(end))) {
                return end;
            }
        }
        return cursor;
    }

    private static int skipWhitespace(String value, int cursor) {
        int index = cursor;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return index;
    }

    private static int endOfToken(String value, int cursor) {
        int index = cursor;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (Character.isWhitespace(current) || current == ',' || current == ';'
                    || current == '"' || current == '\'' || current == '}') {
                break;
            }
            index++;
        }
        return index;
    }

    private static int endOfUnquotedValue(String value, int cursor) {
        int index = cursor;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current == ',' || current == ';' || current == '}'
                    || current == '"' || current == '\'' || current == '&'
                    || current == '\r' || current == '\n'
                    || startsEncodedDelimiter(value, index)) {
                break;
            }
            index++;
        }
        return index;
    }

    private static int endOfContainer(String value, int cursor) {
        Deque<Character> expectedClosers = new ArrayDeque<>();
        Quote activeQuote = null;
        boolean escaped = false;
        int encodedBackslashes = 0;
        for (int index = cursor; index < value.length(); index++) {
            char current = value.charAt(index);
            if (activeQuote != null) {
                if (activeQuote.escaped()) {
                    if (current == '\\') {
                        encodedBackslashes++;
                        continue;
                    }
                    if (closesEncodedQuote(activeQuote, current, encodedBackslashes)) {
                        activeQuote = null;
                    }
                    encodedBackslashes = 0;
                    continue;
                }
                if (current == activeQuote.value() && !escaped) {
                    activeQuote = null;
                }
                if (current == '\\' && !escaped) {
                    escaped = true;
                } else {
                    escaped = false;
                }
                continue;
            }

            Quote opening = openingQuote(value, index);
            if (opening != null) {
                activeQuote = opening;
                escaped = false;
                encodedBackslashes = 0;
                index += opening.width() - 1;
                continue;
            }
            if (current == '[') {
                expectedClosers.push(']');
            } else if (current == '{') {
                expectedClosers.push('}');
            } else if (current == ']' || current == '}') {
                if (expectedClosers.isEmpty() || expectedClosers.pop() != current) {
                    return value.length();
                }
                if (expectedClosers.isEmpty()) {
                    return index + 1;
                }
            }
        }
        return value.length();
    }

    private static int endOfLine(String value, int cursor) {
        int index = cursor;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current == '\r' || current == '\n' || current == '"'
                    || current == '\'' || current == '&'
                    || startsEncodedDelimiter(value, index)) {
                break;
            }
            index++;
        }
        return index;
    }

    private static boolean startsEncodedDelimiter(String value, int index) {
        if (value.charAt(index) != '\\') {
            return false;
        }
        int afterSlashes = index;
        while (afterSlashes < value.length() && value.charAt(afterSlashes) == '\\') {
            afterSlashes++;
        }
        if (afterSlashes >= value.length()) {
            return false;
        }
        char next = value.charAt(afterSlashes);
        return next == '"' || next == '\''
                || value.regionMatches(true, afterSlashes, "u0026", 0, 5);
    }

    private static String redactJwtSecrets(String value) {
        StringBuilder output = null;
        int appendFrom = 0;
        int cursor = 0;
        while (cursor < value.length()) {
            int tokenEnd = jwtEnd(value, cursor);
            if (tokenEnd < 0) {
                cursor++;
                continue;
            }
            if (output == null) {
                output = new StringBuilder(value.length());
            }
            output.append(value, appendFrom, cursor).append(REDACTED);
            appendFrom = tokenEnd;
            cursor = tokenEnd;
        }
        if (output == null) {
            return value;
        }
        return output.append(value, appendFrom, value.length()).toString();
    }

    private static int jwtEnd(String value, int start) {
        if (!value.startsWith("eyJ", start)
                || (start > 0 && isBase64Url(value.charAt(start - 1)))) {
            return -1;
        }
        int headerTailStart = start + 3;
        int headerEnd = base64UrlSegmentEnd(value, headerTailStart);
        if (headerEnd == headerTailStart
                || headerEnd >= value.length()
                || value.charAt(headerEnd) != '.') {
            return -1;
        }
        int payloadStart = headerEnd + 1;
        int payloadEnd = base64UrlSegmentEnd(value, payloadStart);
        if (payloadEnd == payloadStart
                || payloadEnd >= value.length()
                || value.charAt(payloadEnd) != '.') {
            return -1;
        }
        int signatureStart = payloadEnd + 1;
        int signatureEnd = base64UrlSegmentEnd(value, signatureStart);
        if (signatureEnd == signatureStart) {
            return -1;
        }
        int tokenEnd = signatureEnd;
        return tokenEnd < value.length() && isBase64Url(value.charAt(tokenEnd))
                ? -1
                : tokenEnd;
    }

    private static int base64UrlSegmentEnd(String value, int cursor) {
        int index = cursor;
        while (index < value.length() && isBase64Url(value.charAt(index))) {
            index++;
        }
        return index;
    }

    private static boolean isBase64Url(char value) {
        return value >= 'A' && value <= 'Z'
                || value >= 'a' && value <= 'z'
                || value >= '0' && value <= '9'
                || value == '_'
                || value == '-';
    }

    private static String normalizeKey(String key) {
        return key == null ? "" : key.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
    }

    private static boolean isSecretKeyBoundary(String value, int start) {
        if (start == 0 || !Character.isLetterOrDigit(value.charAt(start - 1))) {
            return true;
        }
        int encodedStart = start - 6;
        return encodedStart >= 0
                && value.charAt(encodedStart) == '\\'
                && value.regionMatches(true, encodedStart + 1, "u0026", 0, 5);
    }

    private static void assertNoSecrets(
            String table,
            List<Map<String, Object>> rows) throws SQLException {
        for (Map<String, Object> row : rows) {
            for (Map.Entry<String, Object> field : row.entrySet()) {
                if (!(field.getValue() instanceof String text)) {
                    continue;
                }
                if (containsUnredactedSecret(text)) {
                    throw new SQLException("secret scan failed for " + table + " id="
                            + Objects.toString(row.get("id"), "?")
                            + " column=" + field.getKey());
                }
            }
        }
    }

    private static void assertValidJson(
            Connection connection,
            String table,
            List<Map<String, Object>> rows) throws SQLException {
        List<String> columns = JSON_COLUMNS.getOrDefault(table, List.of());
        if (columns.isEmpty()) {
            return;
        }
        try (PreparedStatement check = connection.prepareStatement("SELECT JSON_VALID(?)")) {
            for (Map<String, Object> row : rows) {
                for (String column : columns) {
                    Object value = row.get(column);
                    if (value == null) {
                        continue;
                    }
                    check.setObject(1, value);
                    try (ResultSet result = check.executeQuery()) {
                        if (!result.next() || result.getInt(1) != 1) {
                            throw new SQLException("redacted JSON is invalid for " + table
                                    + " id=" + Objects.toString(row.get("id"), "?")
                                    + " column=" + column);
                        }
                    }
                }
            }
        }
    }

    private static boolean containsUnredactedSecret(String text) {
        if (URL_SECRET_SIGNATURE.matcher(text).find()) {
            return true;
        }
        Matcher bearer = BEARER_SECRET.matcher(text);
        while (bearer.find()) {
            if (looksLikeCredential(bearer.group(1), bearer.group(2))) {
                return true;
            }
        }
        for (int cursor = 0; cursor < text.length(); cursor++) {
            if (jwtEnd(text, cursor) > cursor) {
                return true;
            }
        }
        Matcher named = SECRET_KEY.matcher(text);
        int searchFrom = 0;
        while (named.find(searchFrom)) {
            if (!isSecretKeyBoundary(text, named.start())) {
                searchFrom = named.end();
                continue;
            }
            SecretSpan span = secretSpan(text, named.group(), named.end());
            if (span == null) {
                searchFrom = named.end();
                continue;
            }
            String value = text.substring(span.valueStart(), span.valueEnd()).trim();
            if (!value.isEmpty() && !value.startsWith(REDACTED)) {
                return true;
            }
            searchFrom = Math.max(span.valueEnd(), named.end());
        }
        return false;
    }

    private static void runRedactionSelfTest() throws SQLException {
        record RedactionCase(String name, String input, String expected) {
        }
        String secret = "0123456789abcdef0123456789abcdef";
        String jwt3 = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0.c2ln";
        String jwt2 = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ0ZXN0In0";
        List<RedactionCase> positive = List.of(
                new RedactionCase("raw query",
                        "https://x/p?a=1&token=" + secret + "&token_usage=9",
                        "https://x/p?a=1&token=" + REDACTED + "&token_usage=9"),
                new RedactionCase("literal u0026",
                        "https://x/p?a=1\\u0026token=" + secret
                                + "\\u0026token_usage=9",
                        "https://x/p?a=1\\u0026token=" + REDACTED
                                + "\\u0026token_usage=9"),
                new RedactionCase("double escaped u0026",
                        "https://x/p?a=1\\\\u0026token=" + secret
                                + "\\\\u0026token_usage=9",
                        "https://x/p?a=1\\\\u0026token=" + REDACTED
                                + "\\\\u0026token_usage=9"),
                new RedactionCase("raw JSON",
                        "{\"Authorization\":\"Bearer " + secret + "\","
                                + "\"api_key\":\"" + secret + "\",\"token_usage\":9}",
                        "{\"Authorization\":\"Bearer " + REDACTED + "\","
                                + "\"api_key\":\"" + REDACTED + "\",\"token_usage\":9}"),
                new RedactionCase("escaped JSON",
                        "{\\\"Authorization\\\":\\\"Basic dXNlcjpwYXNz\\\","
                                + "\\\"token_usage\\\":9}",
                        "{\\\"Authorization\\\":\\\"Basic " + REDACTED + "\\\","
                                + "\\\"token_usage\\\":9}"),
                new RedactionCase("proxy and cookie",
                        "{\"Proxy-Authorization\":\"Bearer " + secret
                                + "\",\"Set-Cookie\":\"sid=" + secret + "\"}",
                        "{\"Proxy-Authorization\":\"Bearer " + REDACTED
                                + "\",\"Set-Cookie\":\"" + REDACTED + "\"}"),
                new RedactionCase("three segment JWT", jwt3, REDACTED),
                new RedactionCase("bearer token", "Bearer " + secret,
                        "Bearer " + REDACTED),
                new RedactionCase("pure alphabetic bearer",
                        "Bearer ABCDEFGHIJKLMNOPQRSTUVWX", "Bearer " + REDACTED),
                new RedactionCase("basic token", "Basic dXNlcjpwYXNz",
                        "Basic " + REDACTED),
                new RedactionCase("multiple query secrets",
                        "api_key=" + secret + "&access_token=" + secret + "&password=p4ss",
                        "api_key=" + REDACTED + "&access_token=" + REDACTED
                                + "&password=" + REDACTED));
        for (RedactionCase fixture : positive) {
            String actual = redactSecrets(fixture.input());
            selfTestCheck(actual.equals(fixture.expected()),
                    fixture.name() + " exact output changed");
            selfTestCheck(redactSecrets(actual).equals(actual),
                    fixture.name() + " is not idempotent");
            selfTestCheck(containsUnredactedSecret(fixture.input()),
                    fixture.name() + " raw input escaped the independent scanner");
            selfTestCheck(!containsUnredactedSecret(actual),
                    fixture.name() + " sanitized output failed the independent scanner");
        }

        List<String> safeInputs = List.of(
                "token_usage=9 prompt_tokens=1 completion_tokens=2 cache_read_tokens=3 "
                        + "cache_write_tokens=4 reasoning_tokens=5",
                "Bearer authentication and the word password",
                jwt2,
                "api_key_rotation=true",
                "mytoken=value notpassword=value",
                "token=" + REDACTED);
        for (String safe : safeInputs) {
            selfTestCheck(redactSecrets(safe).equals(safe), "safe input changed: " + safe);
            selfTestCheck(!containsUnredactedSecret(safe),
                    "safe input rejected by independent scanner: " + safe);
        }
        System.out.println("redaction self-test passed");
    }

    private static void selfTestCheck(boolean condition, String failure) {
        if (!condition) {
            throw new IllegalStateException("redaction self-test failed: " + failure);
        }
    }

    private static Map<String, Object> transformDiagnosis(
            Map<String, Object> source) {
        Map<String, Object> row = copy(source);
        redactColumns(row, "aggregate_json");
        row.put("dedup_key", null);
        row.put("source_intake_session_id", null);
        row.put("closure_notification_status", "NOT_APPLICABLE");
        row.put("closure_notification_attempts", 0);
        row.put("closure_notification_claimed_by", null);
        row.put("closure_notification_lease_expires_at", null);
        row.put("closure_notification_next_attempt_at", null);
        row.put("closure_notification_last_error", null);
        row.put("closure_notification_completed_at", null);
        return row;
    }

    private static TablePlan planSourceTable(
            Connection connection,
            String sourceSchema,
            String targetSchema,
            String table,
            String where,
            UnaryOperator<Map<String, Object>> transform) throws SQLException {
        requireAllowed(table);
        List<String> sourceColumns = columns(connection, sourceSchema, table);
        List<String> targetColumns = columns(connection, targetSchema, table);
        requireEqual("column inventory for " + table,
                new LinkedHashSet<>(sourceColumns), new LinkedHashSet<>(targetColumns));
        List<Map<String, Object>> rows = loadRows(
                connection, sourceSchema, table, sourceColumns, where).stream()
                .map(transform)
                .toList();
        assertNoSecrets(table, rows);
        assertValidJson(connection, table, rows);
        return planRows(connection, targetSchema, table, rows);
    }

    private static TablePlan planRows(
            Connection connection,
            String targetSchema,
            String table,
            List<Map<String, Object>> sourceRows) throws SQLException {
        requireAllowed(table);
        List<String> targetColumns = columns(connection, targetSchema, table);
        for (Map<String, Object> row : sourceRows) {
            requireEqual("row columns for " + table,
                    new LinkedHashSet<>(targetColumns), new LinkedHashSet<>(row.keySet()));
        }
        List<Map<String, Object>> targetRows = loadRows(
                connection, targetSchema, table, targetColumns, "1 = 1");
        List<UniqueIndex> indexes = uniqueIndexes(connection, targetSchema, table);
        if (indexes.stream().noneMatch(index -> "PRIMARY".equals(index.name()))) {
            throw new SQLException(table + " has no primary key");
        }

        Map<String, Map<Key, RowRef>> targetLookups = lookups(indexes, targetRows, table, "target");
        Map<String, Map<Key, RowRef>> plannedLookups = new LinkedHashMap<>();
        for (UniqueIndex index : indexes) {
            plannedLookups.put(index.name(), new LinkedHashMap<>());
        }

        List<Map<String, Object>> inserts = new ArrayList<>();
        long exact = 0;
        for (Map<String, Object> sourceRow : sourceRows) {
            Set<RowRef> matches = new LinkedHashSet<>();
            for (UniqueIndex index : indexes) {
                Key key = key(index, sourceRow);
                if (key == null) {
                    continue;
                }
                RowRef target = targetLookups.get(index.name()).get(key);
                if (target != null) {
                    matches.add(target);
                }
                RowRef planned = plannedLookups.get(index.name()).get(key);
                if (planned != null) {
                    matches.add(planned);
                }
            }
            if (matches.size() > 1) {
                throw conflict(table, sourceRow,
                        "different unique keys resolve to different rows");
            }
            if (matches.size() == 1) {
                RowRef existing = matches.iterator().next();
                if (!sameRow(targetColumns, sourceRow, existing.row())) {
                    throw conflict(table, sourceRow,
                            "unique key already exists with different values");
                }
                exact++;
                continue;
            }

            RowRef ref = new RowRef(inserts.size(), sourceRow);
            for (UniqueIndex index : indexes) {
                Key key = key(index, sourceRow);
                if (key != null) {
                    plannedLookups.get(index.name()).put(key, ref);
                }
            }
            inserts.add(sourceRow);
        }
        return new TablePlan(table, targetColumns, sourceRows.size(), inserts, exact);
    }

    private static Map<String, Map<Key, RowRef>> lookups(
            List<UniqueIndex> indexes,
            List<Map<String, Object>> rows,
            String table,
            String side) throws SQLException {
        Map<String, Map<Key, RowRef>> result = new LinkedHashMap<>();
        for (UniqueIndex index : indexes) {
            result.put(index.name(), new LinkedHashMap<>());
        }
        for (int rowNumber = 0; rowNumber < rows.size(); rowNumber++) {
            Map<String, Object> row = rows.get(rowNumber);
            RowRef ref = new RowRef(rowNumber, row);
            for (UniqueIndex index : indexes) {
                Key key = key(index, row);
                if (key == null) {
                    continue;
                }
                RowRef previous = result.get(index.name()).putIfAbsent(key, ref);
                if (previous != null) {
                    throw new SQLException(side + " " + table + " violates unique index "
                            + index.name());
                }
            }
        }
        return result;
    }

    private static Key key(UniqueIndex index, Map<String, Object> row) {
        List<Object> values = new ArrayList<>();
        for (String column : index.columns()) {
            Object value = row.get(column);
            if (value == null) {
                return null;
            }
            values.add(normalize(value));
        }
        return new Key(List.copyOf(values));
    }

    private static boolean sameRow(
            List<String> columns,
            Map<String, Object> left,
            Map<String, Object> right) {
        for (String column : columns) {
            if (!Objects.equals(normalize(left.get(column)), normalize(right.get(column)))) {
                return false;
            }
        }
        return true;
    }

    private static Object normalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros();
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString()).stripTrailingZeros();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof LocalDateTime || value instanceof LocalDate) {
            return value;
        }
        return value.toString();
    }

    private static SQLException conflict(
            String table,
            Map<String, Object> row,
            String reason) {
        return new SQLException("history merge conflict in " + table + " id="
                + Objects.toString(row.get("id"), "?") + ": " + reason);
    }

    private static void insertRows(
            Connection connection,
            String targetSchema,
            TablePlan plan) throws SQLException {
        if (plan.inserts().isEmpty()) {
            return;
        }
        String names = String.join(", ", plan.columns().stream().map(Main::quote).toList());
        String markers = String.join(", ", Collections.nCopies(plan.columns().size(), "?"));
        String sql = "INSERT INTO " + table(targetSchema, plan.table())
                + " (" + names + ") VALUES (" + markers + ")";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            int pending = 0;
            for (Map<String, Object> row : plan.inserts()) {
                for (int i = 0; i < plan.columns().size(); i++) {
                    insert.setObject(i + 1, row.get(plan.columns().get(i)));
                }
                insert.addBatch();
                if (++pending == BATCH_SIZE) {
                    insert.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                insert.executeBatch();
            }
        }
    }

    private static void validateInsertedCounts(
            Connection connection,
            String targetSchema,
            List<TablePlan> plans,
            Map<String, Long> before) throws SQLException {
        for (TablePlan plan : plans) {
            long expected = before.get(plan.table()) + plan.inserts().size();
            long actual = count(connection, targetSchema, plan.table());
            if (actual != expected) {
                throw new SQLException("row-count verification failed for " + plan.table()
                        + ": expected=" + expected + ", actual=" + actual);
            }
        }
    }

    private static void validateImportedConversationCounts(
            Connection connection,
            String targetSchema,
            TablePlan conversations) throws SQLException {
        for (Map<String, Object> row : conversations.inserts()) {
            String conversationId = Objects.toString(row.get("conversation_id"));
            long actual = queryLong(connection,
                    "SELECT COUNT(*) FROM " + table(targetSchema, "mate_message")
                            + " WHERE deleted = 0 AND conversation_id = ?",
                    conversationId);
            long expected = ((Number) row.get("message_count")).longValue();
            if (actual != expected) {
                throw new SQLException("message_count verification failed for conversation "
                        + conversationId + ": expected=" + expected + ", actual=" + actual);
            }
        }
    }

    private static Map<String, Long> safetyMetrics(
            Connection connection,
            String targetSchema) throws SQLException {
        Map<String, Long> metrics = new LinkedHashMap<>();
        metrics.put("message_without_conversation", queryLong(connection,
                "SELECT COUNT(*) FROM " + table(targetSchema, "mate_message")
                        + " m LEFT JOIN " + table(targetSchema, "mate_conversation")
                        + " c ON c.conversation_id = m.conversation_id AND c.deleted = 0 "
                        + "WHERE m.deleted = 0 AND c.id IS NULL"));
        metrics.put("conversation_without_agent", queryLong(connection,
                "SELECT COUNT(*) FROM " + table(targetSchema, "mate_conversation")
                        + " c LEFT JOIN " + table(targetSchema, "mate_agent")
                        + " a ON a.id = c.agent_id AND a.deleted = 0 "
                        + "WHERE c.deleted = 0 AND c.agent_id IS NOT NULL AND a.id IS NULL"));
        metrics.put("diagnosis_without_intake", queryLong(connection,
                "SELECT COUNT(*) FROM " + table(targetSchema, "mate_troubleshooting_diagnosis")
                        + " d LEFT JOIN "
                        + table(targetSchema, "mate_troubleshooting_intake_session")
                        + " s ON s.workspace_id = d.workspace_id "
                        + "AND s.intake_session_id = d.source_intake_session_id AND s.deleted = 0 "
                        + "WHERE d.deleted = 0 AND d.source_intake_session_id IS NOT NULL "
                        + "AND s.id IS NULL"));
        metrics.put("ready_without_investigation", queryLong(connection,
                "SELECT COUNT(*) FROM "
                        + table(targetSchema, "mate_troubleshooting_intake_session")
                        + " s LEFT JOIN "
                        + table(targetSchema, "mate_troubleshooting_intake_investigation")
                        + " i ON i.workspace_id = s.workspace_id "
                        + "AND i.intake_session_id = s.intake_session_id AND i.deleted = 0 "
                        + "WHERE s.deleted = 0 AND s.status = 'READY' AND i.id IS NULL"));
        metrics.put("dispatchable_intake", queryLong(connection,
                "SELECT COUNT(*) FROM "
                        + table(targetSchema, "mate_troubleshooting_intake_investigation")
                        + " WHERE deleted = 0 AND status IN "
                        + "('PENDING','PROCESSING','FAILED','TERMINAL_PENDING','TERMINAL_PROCESSING')"));
        metrics.put("dispatchable_closure", queryLong(connection,
                "SELECT COUNT(*) FROM " + table(targetSchema, "mate_troubleshooting_diagnosis")
                        + " WHERE deleted = 0 AND closure_notification_status IN "
                        + "('PENDING','PROCESSING','FAILED')"));
        metrics.put("pending_knowledge_outbox", queryLong(connection,
                "SELECT COUNT(*) FROM "
                        + table(targetSchema, "mate_troubleshooting_knowledge_outbox")
                        + " WHERE deleted = 0 AND status IN ('PENDING','PROCESSING','FAILED')"));
        return metrics;
    }

    private static Map<String, Long> protectedCounts(
            Connection connection,
            String targetSchema) throws SQLException {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : PROTECTED_TABLES) {
            if (tableExists(connection, targetSchema, table)) {
                counts.put(table, count(connection, targetSchema, table));
            }
        }
        return counts;
    }

    private static void requireNotIncreased(
            Map<String, Long> before,
            Map<String, Long> after) throws SQLException {
        for (Map.Entry<String, Long> metric : before.entrySet()) {
            long current = after.getOrDefault(metric.getKey(), Long.MAX_VALUE);
            if (current > metric.getValue()) {
                throw new SQLException("safety metric increased: " + metric.getKey()
                        + " before=" + metric.getValue() + " after=" + current);
            }
        }
    }

    private static int validateFlyway(
            Connection connection,
            String schema,
            boolean source) throws SQLException {
        long failures = queryLong(connection,
                "SELECT COUNT(*) FROM " + table(schema, "flyway_schema_history")
                        + " WHERE success = 0");
        if (failures != 0) {
            throw new SQLException(schema + " has " + failures + " failed Flyway migrations");
        }
        int version = (int) queryLong(connection,
                "SELECT CAST(version AS UNSIGNED) FROM "
                        + table(schema, "flyway_schema_history")
                        + " WHERE success = 1 AND version REGEXP '^[0-9]+$' "
                        + "ORDER BY installed_rank DESC LIMIT 1");
        if (source) {
            if (version != EXPECTED_SOURCE_VERSION) {
                throw new SQLException("source Flyway version must be V"
                        + EXPECTED_SOURCE_VERSION + ", got V" + version);
            }
        } else if (version != EXPECTED_TARGET_VERSION) {
            throw new SQLException("target Flyway version must be V"
                    + EXPECTED_TARGET_VERSION + ", got V" + version);
        }
        return version;
    }

    private static List<String> columns(
            Connection connection,
            String schema,
            String table) throws SQLException {
        List<String> columns = new ArrayList<>();
        String sql = "SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = ? ORDER BY ordinal_position";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    columns.add(rows.getString(1).toLowerCase(Locale.ROOT));
                }
            }
        }
        if (columns.isEmpty()) {
            throw new SQLException("required table is missing: " + schema + "." + table);
        }
        return columns;
    }

    private static List<UniqueIndex> uniqueIndexes(
            Connection connection,
            String schema,
            String table) throws SQLException {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        String sql = "SELECT index_name, column_name FROM information_schema.statistics "
                + "WHERE table_schema = ? AND table_name = ? AND non_unique = 0 "
                + "ORDER BY CASE WHEN index_name = 'PRIMARY' THEN 0 ELSE 1 END, "
                + "index_name, seq_in_index";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    grouped.computeIfAbsent(rows.getString(1), ignored -> new ArrayList<>())
                            .add(rows.getString(2).toLowerCase(Locale.ROOT));
                }
            }
        }
        return grouped.entrySet().stream()
                .map(entry -> new UniqueIndex(entry.getKey(), List.copyOf(entry.getValue())))
                .toList();
    }

    private static List<Map<String, Object>> loadRows(
            Connection connection,
            String schema,
            String table,
            List<String> columns,
            String where) throws SQLException {
        String names = String.join(", ", columns.stream()
                .map(column -> "t." + quote(column)).toList());
        String sql = "SELECT " + names + " FROM " + table(schema, table)
                + " t WHERE " + where + " ORDER BY t." + quote("id");
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            while (result.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < columns.size(); i++) {
                    row.put(columns.get(i), result.getObject(i + 1));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static long count(
            Connection connection,
            String schema,
            String table) throws SQLException {
        return queryLong(connection, "SELECT COUNT(*) FROM " + table(schema, table));
    }

    private static long queryLong(Connection connection, String sql, Object... args)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw new SQLException("query returned no rows");
                }
                return rows.getLong(1);
            }
        }
    }

    private static void requireSchema(Connection connection, String schema) throws SQLException {
        long found = queryLong(connection,
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name = ?",
                schema);
        if (found != 1) {
            fail("schema does not exist: " + schema);
        }
    }

    private static boolean tableExists(
            Connection connection,
            String schema,
            String table) throws SQLException {
        return queryLong(connection,
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = ? AND table_name = ?",
                schema, table) == 1;
    }

    private static void requireMysql(Connection connection) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String product = metadata.getDatabaseProductName();
        if (product == null || !product.toLowerCase(Locale.ROOT).contains("mysql")) {
            fail("this tool only supports MySQL, connected product=" + product);
        }
    }

    private static String currentSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT DATABASE()")) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static void acquireLock(Connection connection) throws SQLException {
        long acquired = queryLong(connection, "SELECT GET_LOCK(?, 0)", LOCK_NAME);
        if (acquired != 1) {
            fail("another history merge holds advisory lock " + LOCK_NAME);
        }
    }

    private static void releaseLock(Connection connection) {
        try {
            queryLong(connection, "SELECT RELEASE_LOCK(?)", LOCK_NAME);
        } catch (SQLException ignored) {
            // Closing the connection releases the advisory lock as a final guard.
        }
    }

    private static void exec(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void requireAllowed(String table) {
        if (!ALLOWED_TABLES.contains(table)) {
            throw new IllegalArgumentException("history table is not allow-listed: " + table);
        }
    }

    private static String requireIdentifier(String value, String label) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            fail(label + " must match " + IDENTIFIER.pattern());
        }
        return value;
    }

    private static String quote(String identifier) {
        requireIdentifier(identifier, "SQL identifier");
        return "`" + identifier + "`";
    }

    private static String table(String schema, String table) {
        return quote(schema) + "." + quote(table);
    }

    private static Map<String, Object> copy(Map<String, Object> row) {
        return new LinkedHashMap<>(row);
    }

    private static void requireEqual(String label, Object expected, Object actual)
            throws SQLException {
        if (!Objects.equals(expected, actual)) {
            throw new SQLException(label + " differs: expected=" + expected + ", actual=" + actual);
        }
    }

    private static void fail(String message) {
        System.err.println("refusing to run: " + message);
        System.exit(3);
    }

    private static void usage() {
        System.err.println("usage: <plan|apply> <mysql-jdbc-url> <mysql-user> "
                + "<source-schema> <target-schema>\npassword must be supplied via "
                + PASSWORD_ENV);
    }

    private record Context(
            Map<String, Integer> messageCounts,
            long sourceTaskMessages,
            String conversationPrefix) {
    }

    private record AgentRef(long workspaceId, long agentId) {
    }

    private record SecretSpan(int valueStart, int valueEnd) {
    }

    private record Quote(char value, int backslashCount) {
        private boolean escaped() {
            return backslashCount > 0;
        }

        private int width() {
            return backslashCount + 1;
        }
    }

    private record UniqueIndex(String name, List<String> columns) {
    }

    private record Key(List<Object> values) {
    }

    private record RowRef(int ordinal, Map<String, Object> row) {
    }

    private record TablePlan(
            String table,
            List<String> columns,
            long sourceRows,
            List<Map<String, Object>> inserts,
            long exactRows) {
    }
}
