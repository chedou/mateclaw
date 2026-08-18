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

    private static final Set<String> INCIDENT_REPORTED_SIGNAL_KINDS = Set.of(
            "incident_reported_external_http_failure",
            "incident_reported_business_policy_rejection");

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
            Map.entry("cti_failure_pattern_scan", scalar(Map.of(
                    "failure_request_count", FieldType.NUMBER,
                    "classified_failure_request_count", FieldType.NUMBER,
                    "missing_required_code_request_count", FieldType.NUMBER,
                    "downstream_record_not_found_request_count", FieldType.NUMBER))),
            Map.entry("external_api_http_failure", scalar(Map.of(
                    "failure_count", FieldType.NUMBER,
                    "affected_trace_count", FieldType.NUMBER,
                    "http_status", FieldType.STRING,
                    "operation", FieldType.STRING))),
            Map.entry("incident_reported_external_http_failure", scalar(Map.of(
                    "failure_count", FieldType.NUMBER,
                    "http_status", FieldType.STRING,
                    "operation", FieldType.STRING,
                    "evidence_grade", FieldType.STRING))),
            Map.entry("incident_reported_business_policy_rejection", scalar(Map.of(
                    "failure_count", FieldType.NUMBER,
                    "operation", FieldType.STRING,
                    "policy_code", FieldType.STRING,
                    "client_surface", FieldType.STRING,
                    "change_order_linked", FieldType.BOOLEAN,
                    "recommended_channel", FieldType.STRING,
                    "required_information", FieldType.STRING,
                    "required_information_missing", FieldType.BOOLEAN,
                    "recommended_action", FieldType.STRING,
                    "evidence_grade", FieldType.STRING),
                    Set.of(
                            "client_surface", "change_order_linked", "recommended_channel",
                            "required_information", "required_information_missing",
                            "recommended_action"))),
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
            // Service-scoped Guance object/metric views. Field names stay
            // disjoint from k8s_workload_health so detectSignalKind stays unique.
            Map.entry("k8s_pod_status", scalar(Map.of(
                    "pod_count", FieldType.NUMBER,
                    "running_pod_count", FieldType.NUMBER,
                    "non_running_pod_count", FieldType.NUMBER))),
            Map.entry("k8s_node_status", scalar(
                    Map.of(
                            "node_count", FieldType.NUMBER,
                            "related_host_count", FieldType.NUMBER,
                            "max_node_cpu_percent", FieldType.NUMBER,
                            "max_node_memory_percent", FieldType.NUMBER),
                    Set.of("max_node_cpu_percent", "max_node_memory_percent"))),
            Map.entry("host_status", scalar(
                    Map.of(
                            "host_count", FieldType.NUMBER,
                            "max_host_cpu_percent", FieldType.NUMBER,
                            "max_host_memory_percent", FieldType.NUMBER),
                    Set.of("max_host_cpu_percent", "max_host_memory_percent"))),
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
            case "cti_failure_pattern_scan" -> validCtiFailurePatternScan(observed);
            case "external_api_http_failure" -> validExternalApiHttpFailure(observed);
            case "incident_reported_external_http_failure" ->
                    validIncidentReportedExternalHttpFailure(observed);
            case "incident_reported_business_policy_rejection" ->
                    validIncidentReportedBusinessPolicyRejection(observed);
            case "k8s_workload_health" -> validK8sWorkloadHealth(observed);
            case "k8s_pod_status" -> validK8sPodStatus(observed);
            case "k8s_node_status" -> validK8sNodeStatus(observed);
            case "host_status" -> validHostStatus(observed);
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
     * Canonical signals that may be bound to an external telemetry adapter.
     * Incident-reported facts are canonical for projection, but only the local
     * {@code incident-report} tool is allowed to produce them.
     */
    public static List<String> externallyRoutableSignalKinds() {
        return SCHEMAS.keySet().stream()
                .filter(signal -> !INCIDENT_REPORTED_SIGNAL_KINDS.contains(signal))
                .sorted()
                .toList();
    }

    /** Whether the fact came from a reviewed, normalized incident report. */
    public static boolean isIncidentReported(String signalKind) {
        return signalKind != null
                && INCIDENT_REPORTED_SIGNAL_KINDS.contains(normalize(signalKind));
    }

    public static boolean isExternallyRoutable(String signalKind) {
        return signalKind != null
                && externallyRoutableSignalKinds().contains(normalize(signalKind));
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

    private static boolean validCtiFailurePatternScan(Map<String, Object> observed) {
        if (!validNonNegativeCounts(
                observed,
                "failure_request_count",
                "classified_failure_request_count",
                "missing_required_code_request_count",
                "downstream_record_not_found_request_count")) {
            return false;
        }
        Long total = CanonicalNumberParser.parseExactLong(observed.get("failure_request_count"));
        Long classified = CanonicalNumberParser.parseExactLong(
                observed.get("classified_failure_request_count"));
        Long missingCode = CanonicalNumberParser.parseExactLong(
                observed.get("missing_required_code_request_count"));
        Long recordNotFound = CanonicalNumberParser.parseExactLong(
                observed.get("downstream_record_not_found_request_count"));
        if (total == null || classified == null || missingCode == null
                || recordNotFound == null || classified > total
                || missingCode > total || recordNotFound > total
                || Math.max(missingCode, recordNotFound) > classified) {
            return false;
        }
        try {
            return classified <= Math.addExact(missingCode, recordNotFound);
        } catch (ArithmeticException overflow) {
            return false;
        }
    }

    private static boolean validExternalApiHttpFailure(Map<String, Object> observed) {
        if (!validNonNegativeCounts(observed, "failure_count", "affected_trace_count")) {
            return false;
        }
        Long failures = CanonicalNumberParser.parseExactLong(observed.get("failure_count"));
        Long traces = CanonicalNumberParser.parseExactLong(observed.get("affected_trace_count"));
        String status = String.valueOf(observed.get("http_status")).trim();
        String operation = String.valueOf(observed.get("operation")).trim();
        return failures != null
                && traces != null
                && traces <= failures
                && (failures != 0 || traces == 0)
                && "502".equals(status)
                && "get_icare_product_mapping".equals(operation);
    }

    private static boolean validIncidentReportedExternalHttpFailure(
            Map<String, Object> observed) {
        Long failures = CanonicalNumberParser.parseExactLong(observed.get("failure_count"));
        return failures != null
                && failures == 1
                && "502".equals(String.valueOf(observed.get("http_status")).trim())
                && "get_icare_product_mapping".equals(
                        String.valueOf(observed.get("operation")).trim())
                && "REPORTED".equals(String.valueOf(observed.get("evidence_grade")).trim());
    }

    private static boolean validIncidentReportedBusinessPolicyRejection(
            Map<String, Object> observed) {
        Long failures = CanonicalNumberParser.parseExactLong(observed.get("failure_count"));
        if (failures == null || failures != 1
                || !"updateFinish".equals(String.valueOf(observed.get("operation")).trim())
                || !"REPORTED".equals(String.valueOf(observed.get("evidence_grade")).trim())) {
            return false;
        }
        String policyCode = String.valueOf(observed.get("policy_code")).trim();
        if ("mobile_change_order_finish_forbidden".equals(policyCode)) {
            return observed.keySet().equals(Set.of(
                            "failure_count", "operation", "policy_code", "client_surface",
                            "change_order_linked", "recommended_channel", "evidence_grade"))
                    && "MOBILE".equals(String.valueOf(observed.get("client_surface")).trim())
                    && Boolean.TRUE.equals(observed.get("change_order_linked"))
                    && "PC".equals(String.valueOf(observed.get("recommended_channel")).trim());
        }
        if ("required_revisit_result_missing".equals(policyCode)) {
            return observed.keySet().equals(Set.of(
                            "failure_count", "operation", "policy_code", "required_information",
                            "required_information_missing", "recommended_action", "evidence_grade"))
                    && "REVISIT_RESULT".equals(
                            String.valueOf(observed.get("required_information")).trim())
                    && Boolean.TRUE.equals(observed.get("required_information_missing"))
                    && "COMPLETE_REVISIT_FORM".equals(
                            String.valueOf(observed.get("recommended_action")).trim());
        }
        return false;
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

    private static boolean validK8sPodStatus(Map<String, Object> observed) {
        if (!validNonNegativeCounts(
                observed, "pod_count", "running_pod_count", "non_running_pod_count")) {
            return false;
        }
        Long pods = CanonicalNumberParser.parseExactLong(observed.get("pod_count"));
        Long running = CanonicalNumberParser.parseExactLong(observed.get("running_pod_count"));
        Long nonRunning = CanonicalNumberParser.parseExactLong(
                observed.get("non_running_pod_count"));
        return running <= pods
                && nonRunning <= pods
                && running + nonRunning <= pods;
    }

    private static boolean validK8sNodeStatus(Map<String, Object> observed) {
        if (!validNonNegativeCounts(observed, "node_count", "related_host_count")) {
            return false;
        }
        if (observed.containsKey("max_node_cpu_percent")
                && !validNonNegativeFiniteNumber(observed.get("max_node_cpu_percent"))) {
            return false;
        }
        if (observed.containsKey("max_node_memory_percent")
                && !validNonNegativeFiniteNumber(observed.get("max_node_memory_percent"))) {
            return false;
        }
        return true;
    }

    private static boolean validHostStatus(Map<String, Object> observed) {
        if (!validNonNegativeCounts(observed, "host_count")) {
            return false;
        }
        if (observed.containsKey("max_host_cpu_percent")
                && !validNonNegativeFiniteNumber(observed.get("max_host_cpu_percent"))) {
            return false;
        }
        if (observed.containsKey("max_host_memory_percent")
                && !validNonNegativeFiniteNumber(observed.get("max_host_memory_percent"))) {
            return false;
        }
        return true;
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
