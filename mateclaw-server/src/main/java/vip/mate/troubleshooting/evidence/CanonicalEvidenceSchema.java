package vip.mate.troubleshooting.evidence;

import vip.mate.troubleshooting.CanonicalNumberParser;
import vip.mate.troubleshooting.model.BlastRadius;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Canonical evidence vocabulary shared by every source adapter. */
public final class CanonicalEvidenceSchema {

    private static final int MAX_LOG_TRACE_ENTRIES = 500;
    private static final Set<String> OPTIONAL_LOG_ENTRY_FIELDS = Set.of("duration_ms");
    private static final Map<String, FieldType> LOG_ENTRY_FIELDS = Map.of(
            "timestamp", FieldType.NUMBER,
            "service", FieldType.STRING,
            "level", FieldType.STRING,
            "message", FieldType.STRING,
            "duration_ms", FieldType.NUMBER);
    private static final Map<String, FieldType> LOG_TRACE_ROW_FIELDS =
            withPsId(LOG_ENTRY_FIELDS);

    private static final Map<String, SignalSchema> SCHEMAS = Map.ofEntries(
            Map.entry("synthetic_probe", scalar(Map.of(
                    "status_code", FieldType.NUMBER,
                    "target_url", FieldType.STRING,
                    "probe_name", FieldType.STRING))),
            Map.entry("log_count", scalar(Map.of(
                    "count", FieldType.NUMBER,
                    "trace_id", FieldType.STRING))),
            Map.entry("metric", scalar(Map.of(
                    "reachable", FieldType.BOOLEAN,
                    "connections_current", FieldType.NUMBER,
                    "connections_available", FieldType.NUMBER,
                    "slow_query_count", FieldType.NUMBER,
                    "baseline_slow", FieldType.NUMBER))),
            Map.entry("trace", scalar(Map.of(
                    "failed_hop", FieldType.STRING,
                    "status", FieldType.STRING,
                    "duration_ms", FieldType.NUMBER))),
            Map.entry("log_search", scalar(Map.of(
                    "match_count", FieldType.NUMBER,
                    "ps_id", FieldType.STRING,
                    "sample_message", FieldType.STRING))),
            Map.entry("contrast_sample", scalar(Map.of(
                    "discriminating_feature", FieldType.STRING,
                    "failure_sample_count", FieldType.NUMBER,
                    "failure_match_count", FieldType.NUMBER,
                    "success_sample_count", FieldType.NUMBER,
                    "success_match_count", FieldType.NUMBER))),
            Map.entry("error_log_scan", scalar(
                    Map.of(
                            "error_count", FieldType.NUMBER,
                            "affected_trace_count", FieldType.NUMBER,
                            "latest_trace_id", FieldType.STRING),
                    Set.of("affected_trace_count", "latest_trace_id"))),
            Map.entry("monitor_event_scan", scalar(
                    Map.of(
                            "event_count", FieldType.NUMBER,
                            "latest_status", FieldType.STRING,
                            "latest_checker", FieldType.STRING),
                    Set.of("latest_status", "latest_checker"))),
            Map.entry("k8s_workload_health", scalar(Map.of(
                    "pod_count", FieldType.NUMBER,
                    "container_count", FieldType.NUMBER,
                    "running_container_count", FieldType.NUMBER,
                    "unhealthy_container_count", FieldType.NUMBER,
                    "max_cpu_percent", FieldType.NUMBER,
                    "max_memory_percent", FieldType.NUMBER))),
            Map.entry("incident_impact", scalar(
                    Map.of(
                            "function_scope", FieldType.STRING,
                            "affected_customers", FieldType.NUMBER,
                            "affected_users", FieldType.NUMBER,
                            "blast_radius", FieldType.STRING,
                            "observed_at", FieldType.NUMBER),
                    Set.of("affected_customers", "affected_users"))),
            Map.entry("log_trace_bundle", rows(
                    Map.of(
                            "ps_id", FieldType.STRING,
                            "entries", FieldType.LOG_ENTRIES),
                    LOG_TRACE_ROW_FIELDS,
                    OPTIONAL_LOG_ENTRY_FIELDS)));

    private CanonicalEvidenceSchema() {
    }

    static boolean supports(String signalKind) {
        return schema(signalKind) != null;
    }

    /**
     * Whether a canonical field is declared boolean.
     *
     * <p>An adapter reading a numeric source (Prometheus returns numbers for
     * everything, including {@code up}) has to know this to convert rather than
     * guess. Keeping the answer here means there is still one declaration of
     * what the field is, not one per adapter.</p>
     */
    static boolean isBooleanField(String signalKind, String field) {
        SignalSchema schema = schema(signalKind);
        return schema != null && schema.resultFields().get(field) == FieldType.BOOLEAN;
    }

    static Set<String> fields(String signalKind) {
        SignalSchema schema = schema(signalKind);
        if (schema == null) {
            return Set.of();
        }
        return schema.rowFields().isEmpty()
                ? schema.resultFields().keySet()
                : schema.rowFields().keySet();
    }

    public static boolean isValid(String signalKind, Map<String, Object> observed) {
        SignalSchema schema = schema(signalKind);
        if (schema == null || observed == null
                || !validFields(
                        schema.resultFields(), schema.optionalResultFields(), observed)) {
            return false;
        }
        return switch (normalize(signalKind)) {
            case "incident_impact" -> validIncidentImpact(observed);
            case "error_log_scan" -> validErrorLogScan(observed);
            case "monitor_event_scan" -> validMonitorEventScan(observed);
            case "k8s_workload_health" -> validK8sWorkloadHealth(observed);
            default -> true;
        };
    }

    /**
     * The signal kinds this platform actually understands, sorted.
     *
     * <p>存在的理由是让拒绝能把清单说出来：一条指向词表之外的取证路由永远取不到
     * 合法结果，那只可能是打错字，而只报「不认识」等于逼人去猜。</p>
     */
    public static List<String> signalKinds() {
        return SCHEMAS.keySet().stream().sorted().toList();
    }

    /**
     * Detects the unique canonical signal shape without trusting a caller-supplied kind.
     * Canonical result shapes are intentionally disjoint; ambiguous or malformed input
     * is withheld from downstream model projections.
     */
    public static String detectSignalKind(Map<String, Object> observed) {
        String detected = null;
        for (String signalKind : SCHEMAS.keySet().stream().sorted().toList()) {
            if (!isValid(signalKind, observed)) {
                continue;
            }
            if (detected != null) {
                return null;
            }
            detected = signalKind;
        }
        return detected;
    }

    static boolean isRowSet(String signalKind) {
        SignalSchema schema = schema(signalKind);
        return schema != null && !schema.rowFields().isEmpty();
    }

    static boolean isValidRow(String signalKind, Map<String, Object> observed) {
        SignalSchema schema = schema(signalKind);
        if (schema == null || schema.rowFields().isEmpty() || observed == null) {
            return false;
        }
        return validFields(schema.rowFields(), schema.optionalRowFields(), observed);
    }

    private static boolean validFields(
            Map<String, FieldType> schema,
            Set<String> optionalFields,
            Map<String, Object> observed) {
        Set<String> fields = observed.keySet();
        if (!schema.keySet().containsAll(fields)) {
            return false;
        }
        for (String required : schema.keySet()) {
            if (!optionalFields.contains(required) && !fields.contains(required)) {
                return false;
            }
        }
        return observed.entrySet().stream()
                .allMatch(entry -> matches(schema.get(entry.getKey()), entry.getValue()));
    }

    private static SignalSchema schema(String signalKind) {
        return SCHEMAS.get(normalize(signalKind));
    }

    private static SignalSchema scalar(Map<String, FieldType> resultFields) {
        return scalar(resultFields, Set.of());
    }

    private static SignalSchema scalar(
            Map<String, FieldType> resultFields,
            Set<String> optionalResultFields) {
        return new SignalSchema(resultFields, optionalResultFields, Map.of(), Set.of());
    }

    private static SignalSchema rows(
            Map<String, FieldType> resultFields,
            Map<String, FieldType> rowFields,
            Set<String> optionalRowFields) {
        return new SignalSchema(resultFields, Set.of(), rowFields, optionalRowFields);
    }

    private static boolean validIncidentImpact(Map<String, Object> observed) {
        Long customers = observed.containsKey("affected_customers")
                ? CanonicalNumberParser.parseExactLong(observed.get("affected_customers"))
                : null;
        Long users = observed.containsKey("affected_users")
                ? CanonicalNumberParser.parseExactLong(observed.get("affected_users"))
                : null;
        Long observedAt = CanonicalNumberParser.parseExactLong(observed.get("observed_at"));
        if ((observed.containsKey("affected_customers")
                        && (customers == null || customers < 0 || customers > Integer.MAX_VALUE))
                || (observed.containsKey("affected_users")
                        && (users == null || users < 0 || users > Integer.MAX_VALUE))
                || observedAt == null
                || observedAt < 0) {
            return false;
        }
        BlastRadius radius;
        try {
            radius = BlastRadius.valueOf(String.valueOf(observed.get("blast_radius")));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        return customers != null || users != null || radius != BlastRadius.UNKNOWN;
    }

    private static boolean validErrorLogScan(Map<String, Object> observed) {
        if (!validNonNegativeCounts(observed, "error_count", "affected_trace_count")) {
            return false;
        }
        Long errorCount = CanonicalNumberParser.parseExactLong(observed.get("error_count"));
        Long traceCount = observed.containsKey("affected_trace_count")
                ? CanonicalNumberParser.parseExactLong(observed.get("affected_trace_count"))
                : null;
        return traceCount == null || traceCount <= errorCount;
    }

    private static boolean validMonitorEventScan(Map<String, Object> observed) {
        if (!validNonNegativeCounts(observed, "event_count")) {
            return false;
        }
        Long eventCount = CanonicalNumberParser.parseExactLong(observed.get("event_count"));
        boolean hasStatus = observed.containsKey("latest_status");
        boolean hasChecker = observed.containsKey("latest_checker");
        if (eventCount == 0) {
            return !hasStatus && !hasChecker;
        }
        if (!hasStatus || !hasChecker) {
            return false;
        }
        String status = String.valueOf(observed.get("latest_status"))
                .trim().toLowerCase(Locale.ROOT);
        return Set.of("critical", "error", "warning").contains(status);
    }

    private static boolean validK8sWorkloadHealth(Map<String, Object> observed) {
        if (!validNonNegativeCounts(
                observed,
                "pod_count",
                "container_count",
                "running_container_count",
                "unhealthy_container_count")) {
            return false;
        }
        Long pods = CanonicalNumberParser.parseExactLong(observed.get("pod_count"));
        Long containers = CanonicalNumberParser.parseExactLong(observed.get("container_count"));
        Long running = CanonicalNumberParser.parseExactLong(
                observed.get("running_container_count"));
        Long unhealthy = CanonicalNumberParser.parseExactLong(
                observed.get("unhealthy_container_count"));
        return pods <= containers
                && running <= containers
                && unhealthy <= containers
                && running + unhealthy <= containers
                && validNonNegativeFiniteNumber(observed.get("max_cpu_percent"))
                && validNonNegativeFiniteNumber(observed.get("max_memory_percent"));
    }

    private static boolean validNonNegativeCounts(
            Map<String, Object> observed,
            String... fields) {
        for (String field : fields) {
            if (!observed.containsKey(field)) {
                continue;
            }
            Long value = CanonicalNumberParser.parseExactLong(observed.get(field));
            if (value == null || value < 0 || value > Integer.MAX_VALUE) {
                return false;
            }
        }
        return true;
    }

    private static boolean validNonNegativeFiniteNumber(Object raw) {
        if (!(raw instanceof Number number)) {
            return false;
        }
        double value = number.doubleValue();
        return Double.isFinite(value) && value >= 0d;
    }

    private static Map<String, FieldType> withPsId(Map<String, FieldType> fields) {
        Map<String, FieldType> combined = new LinkedHashMap<>();
        combined.put("ps_id", FieldType.STRING);
        combined.putAll(fields);
        return Map.copyOf(combined);
    }

    private static boolean matches(FieldType type, Object value) {
        if (type == null || value == null) {
            return false;
        }
        return switch (type) {
            case NUMBER -> value instanceof Number;
            case BOOLEAN -> value instanceof Boolean;
            case STRING -> value instanceof String text && !text.isBlank();
            case LOG_ENTRIES -> validLogEntries(value);
        };
    }

    private static boolean validLogEntries(Object value) {
        if (!(value instanceof List<?> entries)
                || entries.isEmpty()
                || entries.size() > MAX_LOG_TRACE_ENTRIES) {
            return false;
        }
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> rawEntry)) {
                return false;
            }
            Map<String, Object> canonicalEntry = new LinkedHashMap<>();
            for (Map.Entry<?, ?> field : rawEntry.entrySet()) {
                if (!(field.getKey() instanceof String key)) {
                    return false;
                }
                canonicalEntry.put(key, field.getValue());
            }
            if (!validFields(LOG_ENTRY_FIELDS, OPTIONAL_LOG_ENTRY_FIELDS, canonicalEntry)) {
                return false;
            }
        }
        return true;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private enum FieldType {
        NUMBER,
        BOOLEAN,
        STRING,
        LOG_ENTRIES
    }

    private record SignalSchema(
            Map<String, FieldType> resultFields,
            Set<String> optionalResultFields,
            Map<String, FieldType> rowFields,
            Set<String> optionalRowFields) {
    }
}
