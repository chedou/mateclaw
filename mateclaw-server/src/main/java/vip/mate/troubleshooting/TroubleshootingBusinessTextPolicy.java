package vip.mate.troubleshooting;

import vip.mate.troubleshooting.model.IncidentContext;

import java.util.regex.Pattern;

/** Deterministic boundary for human-facing troubleshooting business text. */
public final class TroubleshootingBusinessTextPolicy {

    public static final int MAX_CLOSURE_SUMMARY_CHARS = 500;
    public static final int MAX_INCIDENT_TEXT_CHARS = 2000;

    private static final String DEVELOPER_ONLY_PLACEHOLDER = "[开发证据已隐藏]";
    private static final Pattern DEVELOPER_ONLY = Pattern.compile(
            "(?is)(?:\\b[LMTO]::|\\bdql\\b|DeveloperEvidenceView|"
                    + "raw[ _-]?logs?|原始日志|全量日志包)");
    private static final Pattern RAW_LOG_BODY = Pattern.compile(
            "(?im)(?:^\\s*\\d{4}-\\d{2}-\\d{2}[T\\s]\\d{2}:\\d{2}:\\d{2}"
                    + ".*\\b(?:TRACE|DEBUG|INFO|WARN|ERROR|FATAL)\\b|"
                    + "^\\s*(?:at\\s+[A-Za-z_$][\\w.$]*\\([^\\r\\n)]*:\\d+\\)|"
                    + "Caused by:|Exception in thread|Traceback \\(most recent call last\\):|"
                    + "File\\s+[\"'][^\"'\\r\\n]+[\"'],\\s*line\\s+\\d+|"
                    + "panic:|goroutine\\s+\\d+\\s+\\[)|"
                    + "(?:^|\\s)(?:timestamp|time|level|logger|thread|message|stack|"
                    + "exception|traceback|goroutine|panic)\\s*[:=])");
    private static final Pattern STRUCTURED_LOG_BODY = Pattern.compile(
            "(?is)\\{.{0," + MAX_INCIDENT_TEXT_CHARS
                    + "}\\\"(?:timestamp|time|level|logger|thread|"
                    + "message|stack|exception|traceback|goroutine|panic)\\\"\\s*:");
    private static final Pattern SCRIPT_STACK_BODY = Pattern.compile(
            "(?im)(?:^\\s*(?:TypeError|ReferenceError|RangeError|SyntaxError|URIError|"
                    + "EvalError|AggregateError):(?:\\s|$)|"
                    + "^\\s*at\\s+(?:(?:async\\s+)?[A-Za-z_$][\\w.$<>]*\\s+)?"
                    + "\\(?[^\\r\\n()]+\\.(?:js|mjs|cjs|ts|tsx|jsx|vue):\\d+"
                    + "(?::\\d+)?\\)?\\s*$|"
                    + "^\\s*[^@\\r\\n]{0,200}@(?:https?|file)://[^\\r\\n]+"
                    + "\\.(?:js|mjs|cjs|ts|tsx|jsx|vue):\\d+(?::\\d+)?\\s*$)");
    private static final Pattern ACCESS_LOG_BODY = Pattern.compile(
            "(?im)^\\s*\\S+\\s+\\S+\\s+\\S+\\s+\\[[^\\r\\n]{1,128}\\]\\s+"
                    + "\"(?:GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|TRACE|CONNECT)\\s+"
                    + "\\S+\\s+HTTP/\\d(?:\\.\\d)?\"\\s+\\d{3}\\s+(?:\\d+|-)"
                    + "(?:\\s+\"[^\"\\r\\n]*\"\\s+\"[^\"\\r\\n]*\")?\\s*$");
    private static final Pattern MENTION = Pattern.compile("(?s)<@[^>\\r\\n]{1,128}>");

    private TroubleshootingBusinessTextPolicy() {
    }

    /**
     * Validates the operator-authored closure summary before it can enter the
     * Diagnosis aggregate, timeline, knowledge candidate or original channel.
     */
    public static String requireSafeClosureSummary(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "closure summary must be business-safe and not blank");
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_CLOSURE_SUMMARY_CHARS) {
            throw new IllegalArgumentException(
                    "closure summary must be at most "
                            + MAX_CLOSURE_SUMMARY_CHARS + " characters");
        }
        if (!normalized.equals(TroubleshootingSecretRedactor.redact(normalized))
                || DEVELOPER_ONLY.matcher(normalized).find()
                || MENTION.matcher(normalized).find()
                || containsUnsafeControlCharacter(normalized)) {
            throw new IllegalArgumentException(
                    "closure summary must be business-safe: credentials, developer evidence, "
                            + "raw mention markup and control characters are forbidden");
        }
        return normalized;
    }

    /**
     * Rejects developer-only payloads before operator/channel text can enter an
     * Incident, a persisted Diagnosis, or the miss-path model prompt.
     * Credentials are redacted by the caller first; DQL and raw log bodies are
     * refused rather than stored behind a placeholder.
     */
    public static void requireNoDeveloperEvidence(String value, String field) {
        if (value == null || value.isBlank()) {
            return;
        }
        String normalized = value.trim();
        if (normalized.length() > MAX_INCIDENT_TEXT_CHARS) {
            throw new IllegalArgumentException(
                    field + " must be at most " + MAX_INCIDENT_TEXT_CHARS + " characters");
        }
        if (DEVELOPER_ONLY.matcher(normalized).find()
                || RAW_LOG_BODY.matcher(normalized).find()
                || STRUCTURED_LOG_BODY.matcher(normalized).find()
                || SCRIPT_STACK_BODY.matcher(normalized).find()
                || ACCESS_LOG_BODY.matcher(normalized).find()
                || containsUnsafeControlCharacter(normalized)) {
            throw new IllegalArgumentException(
                    field + " must not contain DQL, raw logs, stack traces, "
                            + "or unsafe control characters");
        }
    }

    /** Applies the same ingress rule to every persisted/model-visible Incident field. */
    public static void requireNoDeveloperEvidence(IncidentContext incident) {
        if (incident == null) {
            throw new IllegalArgumentException("incident is required");
        }
        requireNoDeveloperEvidence(incident.incidentId(), "incidentId");
        requireNoDeveloperEvidence(incident.system(), "system");
        requireNoDeveloperEvidence(incident.service(), "service");
        requireNoDeveloperEvidence(incident.errorCode(), "errorCode");
        requireNoDeveloperEvidence(incident.title(), "title");
        requireNoDeveloperEvidence(incident.severity(), "severity");
        requireNoDeveloperEvidence(incident.traceId(), "traceId");
        requireNoDeveloperEvidence(incident.slaRemaining(), "slaRemaining");
        requireNoDeveloperEvidence(incident.intakeSource(), "intakeSource");
        requireNoDeveloperEvidence(incident.rawInput(), "rawInput");
        if (incident.impact() != null) {
            requireNoDeveloperEvidence(
                    incident.impact().functionScope(), "impact.functionScope");
            requireNoDeveloperEvidence(incident.impact().note(), "impact.note");
            for (int index = 0; index < incident.impact().evidenceRefs().size(); index++) {
                requireNoDeveloperEvidence(
                        incident.impact().evidenceRefs().get(index),
                        "impact.evidenceRefs[" + index + "]");
            }
        }
    }

    /** Defense in depth for old rows and projections that predate the ingress policy. */
    public static String forChannel(String value, int maxChars) {
        if (maxChars < 1) {
            throw new IllegalArgumentException("channel text budget must be positive");
        }
        String sanitized = TroubleshootingSecretRedactor.redact(
                value == null ? "" : value.trim());
        if (DEVELOPER_ONLY.matcher(sanitized).find()) {
            sanitized = DEVELOPER_ONLY_PLACEHOLDER;
        }
        sanitized = MENTION.matcher(sanitized).replaceAll("[mention 已隐藏]");
        sanitized = sanitized.replace("<@", "＜@");
        sanitized = collapseWhitespace(sanitized);
        if (sanitized.isBlank()) {
            sanitized = "未提供";
        }
        return truncate(sanitized, maxChars);
    }

    public static String truncate(String value, int maxChars) {
        if (value == null) {
            return value;
        }
        if (value.length() <= maxChars) {
            return value;
        }
        if (maxChars == 1) {
            return "…";
        }
        int end = maxChars - 1;
        if (end > 0
                && Character.isHighSurrogate(value.charAt(end - 1))
                && Character.isLowSurrogate(value.charAt(end))) {
            end--;
        }
        return value.substring(0, end) + "…";
    }

    private static String collapseWhitespace(String value) {
        StringBuilder result = new StringBuilder(value.length());
        boolean pendingSpace = false;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isWhitespace(current)) {
                pendingSpace = !result.isEmpty();
                continue;
            }
            if (pendingSpace) {
                result.append(' ');
                pendingSpace = false;
            }
            result.append(current);
        }
        return result.toString();
    }

    private static boolean containsUnsafeControlCharacter(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isISOControl(current)
                    && current != '\n'
                    && current != '\r'
                    && current != '\t') {
                return true;
            }
        }
        return false;
    }
}
