package vip.mate.troubleshooting;

import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.IncidentContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Redacts credentials before troubleshooting data reaches an LLM or Diagnosis. */
public final class TroubleshootingSecretRedactor {

    public static final String REDACTED = "<REDACTED>";

    private static final Pattern SECRET_KEY = Pattern.compile(
            "(?i)(?:authorization|api[-_ ]?key|access[-_ ]?key(?:[-_ ]?id)?|"
                    + "secret[-_ ]?(?:access[-_ ]?)?key|"
                    + "private[-_ ]?key|"
                    + "access[-_ ]?token|refresh[-_ ]?token|session[-_ ]?token|"
                    + "oauth[-_ ]?token|token|client[-_ ]?secret|password|credentials?|"
                    + "cookie|secret)(?![A-Za-z0-9])");
    private static final Pattern BEARER_SECRET = Pattern.compile(
            "(?i)((?:bearer|basic)\\s+)[^\\s,;\\\"'}]+",
            Pattern.MULTILINE);

    private TroubleshootingSecretRedactor() {
    }

    public static String redact(String value) {
        String sanitized = redactNamedSecrets(value == null ? "" : value);
        sanitized = BEARER_SECRET.matcher(sanitized).replaceAll("$1" + REDACTED);
        return redactJwtSecrets(sanitized);
    }

    /**
     * Redacts JWT-shaped tokens in a single forward pass. A bounded regex can
     * miss valid oversized tokens, while an unbounded search regex may rescan
     * the same long no-dot candidate from many offsets. Boundary checks ensure
     * a base64 fragment embedded in a larger identifier is left alone.
     */
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
        if (payloadEnd == payloadStart) {
            return -1;
        }

        int tokenEnd = payloadEnd;
        if (payloadEnd < value.length() && value.charAt(payloadEnd) == '.') {
            int signatureStart = payloadEnd + 1;
            int signatureEnd = base64UrlSegmentEnd(value, signatureStart);
            if (signatureEnd > signatureStart) {
                tokenEnd = signatureEnd;
            }
        }
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

    /**
     * Redacts key/value secrets with a deterministic scanner after a bounded,
     * finite-alternative key match. The value scan handles normal JSON,
     * backslash-escaped JSON and multi-word quoted values without using a
     * backtracking value regex.
     */
    private static String redactNamedSecrets(String value) {
        Matcher matcher = SECRET_KEY.matcher(value);
        StringBuilder output = null;
        int appendFrom = 0;
        int searchFrom = 0;
        while (matcher.find(searchFrom)) {
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
        if (current == '\"' || current == '\'') {
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
            if (encodedQuote == '\"' || encodedQuote == '\'') {
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
                    || current == '\"' || current == '\'' || current == '}') {
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
                    || current == '\r' || current == '\n') {
                break;
            }
            index++;
        }
        return index;
    }

    /**
     * Finds the end of a JSON-like array/object without regex backtracking.
     * Malformed or unclosed containers fail closed by consuming the remaining
     * text, which is preferable to exposing a suffix of a declared secret.
     */
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
                    if (closesEncodedQuote(
                            activeQuote, current, encodedBackslashes)) {
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
        while (index < value.length()
                && value.charAt(index) != '\r'
                && value.charAt(index) != '\n') {
            index++;
        }
        return index;
    }

    public static IncidentContext redact(IncidentContext incident) {
        if (incident == null) {
            return null;
        }
        return new IncidentContext(
                redact(incident.incidentId()),
                redact(incident.system()),
                redact(incident.service()),
                redactNullable(incident.errorCode()),
                redact(incident.title()),
                redact(incident.severity()),
                redact(incident.impact()),
                redactNullable(incident.traceId()),
                incident.occurredAt(),
                redactNullable(incident.slaRemaining()),
                redact(incident.intakeSource()),
                incident.completeness(),
                redactNullable(incident.rawInput()));
    }

    public static EvidenceResult redact(EvidenceResult evidence) {
        if (evidence == null) {
            return null;
        }
        return new EvidenceResult(
                redact(evidence.queryId()),
                redact(evidence.namespace()),
                redact(evidence.query()),
                evidence.status(),
                redact(evidence.summary()),
                redactMap(evidence.observed()),
                redact(evidence.source()),
                evidence.collectedAt());
    }

    private static String redactNullable(String value) {
        return value == null ? null : redact(value);
    }

    private static Map<String, Object> redactMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        UniqueKeyAllocator keys = new UniqueKeyAllocator();
        values.forEach((key, value) -> {
            String sanitizedKey = keys.allocate(sanitized, redact(key));
            sanitized.put(
                    sanitizedKey,
                    isSecretKey(key) ? REDACTED : redactValue(value));
        });
        return Collections.unmodifiableMap(sanitized);
    }

    private static Object redactValue(Object value) {
        if (value instanceof String text) {
            return redact(text);
        }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> sanitized = new LinkedHashMap<>();
            UniqueKeyAllocator keys = new UniqueKeyAllocator();
            map.forEach((key, nestedValue) -> {
                Object sanitizedKey = key instanceof String text
                        ? keys.allocate(sanitized, redact(text))
                        : key;
                sanitized.put(
                        sanitizedKey,
                        isSecretKey(String.valueOf(key))
                                ? REDACTED
                                : redactValue(nestedValue));
            });
            return Collections.unmodifiableMap(sanitized);
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> sanitized = new ArrayList<>();
            iterable.forEach(item -> sanitized.add(redactValue(item)));
            return Collections.unmodifiableList(sanitized);
        }
        if (value instanceof Object[] array) {
            List<Object> sanitized = new ArrayList<>(array.length);
            for (Object item : array) {
                sanitized.add(redactValue(item));
            }
            return Collections.unmodifiableList(sanitized);
        }
        return value;
    }

    /**
     * Allocates collision suffixes with one monotonic cursor per map layer.
     * Each generated candidate is attempted at most once across that layer,
     * so a flood of keys that redact to the same text remains amortized O(n).
     */
    private static final class UniqueKeyAllocator {
        private int nextSuffix = 2;

        private String allocate(Map<?, ?> values, String requested) {
            String base = requested == null || requested.isBlank()
                    ? "redacted-key"
                    : requested;
            if (!values.containsKey(base)) {
                return base;
            }
            String candidate;
            do {
                candidate = base + "#" + nextSuffix++;
            } while (values.containsKey(candidate));
            return candidate;
        }
    }

    private static boolean isSecretKey(String key) {
        String normalized = normalizeKey(key);
        return normalized.endsWith("authorization")
                || normalized.endsWith("apikey")
                || normalized.endsWith("accesskey")
                || normalized.endsWith("accesskeyid")
                || normalized.endsWith("secretkey")
                || normalized.endsWith("secretaccesskey")
                || normalized.endsWith("privatekey")
                || normalized.endsWith("password")
                || normalized.endsWith("secret")
                || normalized.endsWith("token")
                || normalized.endsWith("credential")
                || normalized.endsWith("credentials")
                || normalized.endsWith("cookie");
    }

    private static String normalizeKey(String key) {
        return key == null ? "" : key
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
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
}
