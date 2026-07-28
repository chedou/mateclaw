package vip.mate.troubleshooting;

import java.util.regex.Pattern;

/** Deterministic boundary for human-facing troubleshooting business text. */
public final class TroubleshootingBusinessTextPolicy {

    public static final int MAX_CLOSURE_SUMMARY_CHARS = 500;

    private static final String DEVELOPER_ONLY_PLACEHOLDER = "[开发证据已隐藏]";
    private static final Pattern DEVELOPER_ONLY = Pattern.compile(
            "(?is)(?:\\b[LMTO]::|\\bdql\\b|DeveloperEvidenceView|"
                    + "raw[ _-]?logs?|原始日志|全量日志包)");
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
