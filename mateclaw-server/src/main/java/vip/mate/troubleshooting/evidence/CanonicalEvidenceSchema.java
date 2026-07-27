package vip.mate.troubleshooting.evidence;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Canonical 903001 evidence vocabulary shared by every source adapter. */
final class CanonicalEvidenceSchema {

    private static final Map<String, Map<String, FieldType>> SCHEMAS = Map.of(
            "log_count", Map.of(
                    "count", FieldType.NUMBER,
                    "trace_id", FieldType.STRING),
            "metric", Map.of(
                    "reachable", FieldType.BOOLEAN,
                    "connections_current", FieldType.NUMBER,
                    "connections_available", FieldType.NUMBER,
                    "slow_query_count", FieldType.NUMBER,
                    "baseline_slow", FieldType.NUMBER),
            "trace", Map.of(
                    "failed_hop", FieldType.STRING,
                    "status", FieldType.STRING,
                    "duration_ms", FieldType.NUMBER));

    private CanonicalEvidenceSchema() {
    }

    static boolean supports(String signalKind) {
        return schema(signalKind) != null;
    }

    static Set<String> fields(String signalKind) {
        Map<String, FieldType> schema = schema(signalKind);
        return schema == null ? Set.of() : schema.keySet();
    }

    static boolean isValid(String signalKind, Map<String, Object> observed) {
        Map<String, FieldType> schema = schema(signalKind);
        if (schema == null || observed == null || !observed.keySet().equals(schema.keySet())) {
            return false;
        }
        return observed.entrySet().stream()
                .allMatch(entry -> matches(schema.get(entry.getKey()), entry.getValue()));
    }

    private static Map<String, FieldType> schema(String signalKind) {
        return SCHEMAS.get(normalize(signalKind));
    }

    private static boolean matches(FieldType type, Object value) {
        if (type == null || value == null) {
            return false;
        }
        return switch (type) {
            case NUMBER -> value instanceof Number;
            case BOOLEAN -> value instanceof Boolean;
            case STRING -> value instanceof String text && !text.isBlank();
        };
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private enum FieldType {
        NUMBER,
        BOOLEAN,
        STRING
    }
}
