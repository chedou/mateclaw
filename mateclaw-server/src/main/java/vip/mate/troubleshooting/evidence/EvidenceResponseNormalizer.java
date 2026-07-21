package vip.mate.troubleshooting.evidence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

final class EvidenceResponseNormalizer {

    private static final Set<String> LOG_ARRAY_KEYS = Set.of("logs", "hits", "records", "items", "events", "entries");
    private static final Set<String> RELEASE_ARRAY_KEYS = Set.of("releases", "changes", "deployments", "records", "items", "events");
    private static final Set<String> METRIC_ARRAY_KEYS = Set.of(
            "metrics", "series", "points", "values", "records", "items", "results", "data"
    );
    private static final Set<String> SYNTHETICS_ARRAY_KEYS = Set.of(
            "synthetics", "checks", "checkresults", "dialtests", "tests", "results", "records", "items", "events",
            "content"
    );
    private static final Set<String> INFRASTRUCTURE_ARRAY_KEYS = Set.of(
            "hosts", "host", "containers", "container", "pods", "pod", "k8s", "infrastructure", "results",
            "records", "items", "events", "content", "data", "series"
    );
    private static final List<String> LOG_FIELDS = List.of(
            "timestamp", "time", "level", "severity", "message", "msg", "traceId", "requestId", "pod", "instance"
    );
    private static final List<String> RELEASE_FIELDS = List.of(
            "time", "timestamp", "startedAt", "finishedAt", "serviceName", "app", "cluster", "env",
            "version", "image", "commitId", "branch", "operator", "owner", "status", "type",
            "changeType", "rollbackAvailable", "rollbackVersion", "title", "summary"
    );
    private static final List<String> METRIC_FIELDS = List.of(
            "time", "timestamp", "metric", "metricName", "name", "series", "serviceName", "env",
            "cluster", "endpoint", "value", "avg", "max", "min", "p95", "p99", "unit", "status", "summary"
    );
    private static final List<String> SYNTHETICS_FIELDS = List.of(
            "time", "timestamp", "checkName", "name", "taskName", "url", "endpoint", "region", "location",
            "node", "status", "success", "available", "availability", "successRate", "latency", "duration",
            "responseTime", "response_time", "httpStatus", "http_status", "statusCode", "status_code", "error",
            "message", "failureReason"
    );
    private static final List<String> INFRASTRUCTURE_FIELDS = List.of(
            "time", "timestamp", "host", "hostName", "host_name", "hostname", "node", "ip", "instance",
            "container", "containerName", "container_name", "container_id", "pod", "podName", "pod_name",
            "namespace", "cluster", "image", "status", "state", "ready", "restartCount", "restart_count",
            "cpu", "cpu_usage", "mem", "memory", "mem_used_percent", "load", "message", "reason"
    );

    private EvidenceResponseNormalizer() {
    }

    static Map<String, Object> logs(ObjectMapper mapper, String rawBody, UnaryOperator<String> redactor) {
        Object parsed = parse(mapper, rawBody);
        Map<String, Object> summary = baseSummary(parsed, rawBody, redactor);
        List<Map<String, Object>> rows = rows(parsed, LOG_ARRAY_KEYS);
        int matchedCount = explicitCount(parsed).orElse(rows.size());
        summary.put("matchedCount", matchedCount);
        summary.put("sampleLogs", rows.stream()
                .limit(3)
                .map(row -> pickFields(row, LOG_FIELDS, redactor, 300))
                .toList());
        List<String> topMessages = rows.stream()
                .map(EvidenceResponseNormalizer::message)
                .flatMap(Optional::stream)
                .map(v -> abbreviate(redactor.apply(v), 300))
                .distinct()
                .limit(3)
                .toList();
        summary.put("topMessages", topMessages);
        summary.put("errorSignatures", topMessages.stream()
                .map(EvidenceResponseNormalizer::signature)
                .distinct()
                .limit(3)
                .toList());
        return summary;
    }

    static Map<String, Object> metrics(ObjectMapper mapper, String rawBody, UnaryOperator<String> redactor) {
        Object parsed = parse(mapper, rawBody);
        Map<String, Object> summary = baseSummary(parsed, rawBody, redactor);
        List<Map<String, Object>> rows = rows(parsed, METRIC_ARRAY_KEYS);
        int seriesCount = explicitCount(parsed).orElse(rows.size());
        List<Map<String, Object>> samples = rows.stream()
                .limit(8)
                .map(row -> pickFields(row, METRIC_FIELDS, redactor, 240))
                .toList();
        summary.put("seriesCount", seriesCount);
        summary.put("sampleMetrics", samples);
        summary.put("metricNames", rows.stream()
                .map(EvidenceResponseNormalizer::metricName)
                .flatMap(Optional::stream)
                .map(v -> abbreviate(redactor.apply(v), 160))
                .distinct()
                .limit(12)
                .toList());
        summary.put("anomalyHints", rows.stream()
                .filter(EvidenceResponseNormalizer::metricLooksAbnormal)
                .map(EvidenceResponseNormalizer::metricHint)
                .flatMap(Optional::stream)
                .map(v -> abbreviate(redactor.apply(v), 240))
                .distinct()
                .limit(8)
                .toList());
        return summary;
    }

    static Map<String, Object> releases(ObjectMapper mapper, String rawBody, UnaryOperator<String> redactor) {
        Object parsed = parse(mapper, rawBody);
        Map<String, Object> summary = baseSummary(parsed, rawBody, redactor);
        List<Map<String, Object>> rows = rows(parsed, RELEASE_ARRAY_KEYS);
        int changeCount = explicitCount(parsed).orElse(rows.size());
        List<Map<String, Object>> changes = rows.stream()
                .limit(5)
                .map(row -> pickFields(row, RELEASE_FIELDS, redactor, 240))
                .toList();
        summary.put("changeCount", changeCount);
        summary.put("changes", changes);
        if (!changes.isEmpty()) {
            summary.put("latestChange", changes.get(0));
        }
        summary.put("rollbackAvailable", rows.stream().anyMatch(EvidenceResponseNormalizer::rollbackAvailable));
        return summary;
    }

    static Map<String, Object> synthetics(ObjectMapper mapper, String rawBody, UnaryOperator<String> redactor) {
        Object parsed = parse(mapper, rawBody);
        Map<String, Object> summary = baseSummary(parsed, rawBody, redactor);
        List<Map<String, Object>> rows = rows(parsed, SYNTHETICS_ARRAY_KEYS);
        int checkCount = explicitCount(parsed).orElse(rows.size());
        List<Map<String, Object>> samples = rows.stream()
                .limit(5)
                .map(row -> pickFields(row, SYNTHETICS_FIELDS, redactor, 240))
                .toList();
        List<Map<String, Object>> failedRows = rows.stream()
                .filter(EvidenceResponseNormalizer::syntheticFailed)
                .toList();
        long failedCount = rows.stream().filter(EvidenceResponseNormalizer::syntheticFailed).count();
        summary.put("checkCount", checkCount);
        summary.put("failedCount", failedCount);
        summary.put("successCount", Math.max(0, rows.size() - failedCount));
        summary.put("successRate", rows.isEmpty() ? null : roundRatio((rows.size() - failedCount) * 100.0 / rows.size()));
        summary.put("failureRate", rows.isEmpty() ? null : roundRatio(failedCount * 100.0 / rows.size()));
        summary.put("sampleChecks", samples);
        summary.put("failedChecks", failedRows.stream()
                .limit(5)
                .map(row -> pickFields(row, SYNTHETICS_FIELDS, redactor, 240))
                .toList());
        summary.put("taskNames", rows.stream()
                .map(EvidenceResponseNormalizer::syntheticName)
                .flatMap(Optional::stream)
                .map(v -> abbreviate(redactor.apply(v), 160))
                .distinct()
                .limit(8)
                .toList());
        summary.put("urls", rows.stream()
                .map(EvidenceResponseNormalizer::url)
                .flatMap(Optional::stream)
                .map(v -> abbreviate(redactor.apply(v), 240))
                .distinct()
                .limit(8)
                .toList());
        summary.put("failedUrls", failedRows.stream()
                .map(EvidenceResponseNormalizer::url)
                .flatMap(Optional::stream)
                .map(v -> abbreviate(redactor.apply(v), 240))
                .distinct()
                .limit(8)
                .toList());
        summary.put("statusCodes", rows.stream()
                .map(EvidenceResponseNormalizer::statusCode)
                .flatMap(Optional::stream)
                .distinct()
                .limit(12)
                .toList());
        summary.put("failedStatusCodes", failedRows.stream()
                .map(EvidenceResponseNormalizer::statusCode)
                .flatMap(Optional::stream)
                .distinct()
                .limit(12)
                .toList());
        summary.put("affectedRegions", failedRows.stream()
                .map(EvidenceResponseNormalizer::region)
                .flatMap(Optional::stream)
                .map(v -> abbreviate(redactor.apply(v), 160))
                .distinct()
                .limit(8)
                .toList());
        summary.put("affectedNodes", failedRows.stream()
                .map(EvidenceResponseNormalizer::node)
                .flatMap(Optional::stream)
                .map(v -> abbreviate(redactor.apply(v), 160))
                .distinct()
                .limit(8)
                .toList());
        summary.put("failureReasons", failedRows.stream()
                .map(EvidenceResponseNormalizer::failureReason)
                .flatMap(Optional::stream)
                .map(v -> abbreviate(redactor.apply(v), 240))
                .distinct()
                .limit(5)
                .toList());
        summary.put("diagnosisSignals", syntheticsDiagnosisSignals(summary, failedCount));
        summary.put("availabilityConclusion", syntheticsConclusion(summary, rows.size(), failedCount));
        return summary;
    }

    static Map<String, Object> infrastructure(ObjectMapper mapper,
                                              String rawBody,
                                              UnaryOperator<String> redactor,
                                              String evidenceType) {
        Object parsed = parse(mapper, rawBody);
        Map<String, Object> summary = baseSummary(parsed, rawBody, redactor);
        List<Map<String, Object>> rows = rows(parsed, INFRASTRUCTURE_ARRAY_KEYS);
        int recordCount = explicitCount(parsed).orElse(rows.size());
        List<Map<String, Object>> samples = rows.stream()
                .limit(8)
                .map(row -> pickFields(row, INFRASTRUCTURE_FIELDS, redactor, 240))
                .toList();
        List<Map<String, Object>> abnormalRows = rows.stream()
                .filter(EvidenceResponseNormalizer::infrastructureLooksAbnormal)
                .toList();
        summary.put("evidenceType", evidenceType);
        summary.put("recordCount", recordCount);
        summary.put("sampleRecords", samples);
        summary.put("objectNames", rows.stream()
                .map(EvidenceResponseNormalizer::infrastructureName)
                .flatMap(Optional::stream)
                .map(v -> abbreviate(redactor.apply(v), 160))
                .distinct()
                .limit(12)
                .toList());
        summary.put("abnormalCount", abnormalRows.size());
        summary.put("unhealthyObjects", abnormalRows.stream()
                .map(EvidenceResponseNormalizer::infrastructureName)
                .flatMap(Optional::stream)
                .map(v -> abbreviate(redactor.apply(v), 160))
                .distinct()
                .limit(12)
                .toList());
        summary.put("abnormalStates", abnormalRows.stream()
                .map(EvidenceResponseNormalizer::infrastructureState)
                .flatMap(Optional::stream)
                .map(v -> abbreviate(redactor.apply(v), 240))
                .distinct()
                .limit(8)
                .toList());
        summary.put("restartObjects", rows.stream()
                .map(EvidenceResponseNormalizer::infrastructureRestartSignal)
                .flatMap(Optional::stream)
                .map(v -> abbreviate(redactor.apply(v), 240))
                .distinct()
                .limit(8)
                .toList());
        summary.put("resourcePressure", rows.stream()
                .map(EvidenceResponseNormalizer::resourcePressureSignal)
                .flatMap(Optional::stream)
                .map(v -> abbreviate(redactor.apply(v), 240))
                .distinct()
                .limit(8)
                .toList());
        summary.put("infrastructureSignals", infrastructureDiagnosisSignals(summary, evidenceType));
        summary.put("infrastructureConclusion", infrastructureConclusion(summary, evidenceType, rows.size(), abnormalRows.size()));
        return summary;
    }

    @SuppressWarnings("unchecked")
    private static Object parse(ObjectMapper mapper, String rawBody) {
        if (rawBody == null || rawBody.isBlank()) return null;
        try {
            return mapper.readValue(rawBody, new TypeReference<>() {
            });
        } catch (Exception ignored) {
            return rawBody;
        }
    }

    private static Map<String, Object> baseSummary(Object parsed, String rawBody, UnaryOperator<String> redactor) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("parsed", !(parsed instanceof String) && parsed != null);
        summary.put("rawType", parsed == null ? "empty" : parsed.getClass().getSimpleName());
        if (parsed instanceof String || parsed == null) {
            summary.put("textPreview", abbreviate(redactor.apply(rawBody == null ? "" : rawBody), 500));
        }
        return summary;
    }

    private static List<Map<String, Object>> rows(Object parsed, Set<String> candidateKeys) {
        if (parsed instanceof List<?> list) {
            return maps(list);
        }
        List<?> found = findArray(parsed, candidateKeys);
        return maps(found == null ? List.of() : found);
    }

    private static List<?> findArray(Object value, Set<String> candidateKeys) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT);
                if (candidateKeys.contains(key) && entry.getValue() instanceof List<?> list) {
                    return list;
                }
            }
            for (Object nested : map.values()) {
                List<?> found = findArray(nested, candidateKeys);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Optional<Integer> explicitCount(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (String key : List.of("total", "totalCount", "count", "matchedCount", "size")) {
                Object count = getIgnoreCase(map, key);
                Integer parsed = integer(count);
                if (parsed != null) return Optional.of(parsed);
            }
            for (Object nested : map.values()) {
                Optional<Integer> found = explicitCount(nested);
                if (found.isPresent()) return found;
            }
        }
        return Optional.empty();
    }

    private static List<Map<String, Object>> maps(Collection<?> values) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    row.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private static Map<String, Object> pickFields(Map<String, Object> row,
                                                  List<String> fields,
                                                  UnaryOperator<String> redactor,
                                                  int maxChars) {
        Map<String, Object> picked = new LinkedHashMap<>();
        for (String field : fields) {
            Object value = getIgnoreCase(row, field);
            if (value == null) continue;
            if (value instanceof Boolean || value instanceof Number) {
                picked.put(field, value);
            } else if (value instanceof CharSequence text) {
                picked.put(field, abbreviate(redactor.apply(text.toString()), maxChars));
            } else {
                picked.put(field, abbreviate(redactor.apply(String.valueOf(value)), maxChars));
            }
        }
        return picked;
    }

    private static Object getIgnoreCase(Map<?, ?> map, String expected) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (expected.equalsIgnoreCase(String.valueOf(entry.getKey()))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static Optional<String> message(Map<String, Object> row) {
        for (String field : List.of("message", "msg", "error", "exception", "stack", "content")) {
            Object value = getIgnoreCase(row, field);
            if (value instanceof CharSequence text && !text.toString().isBlank()) {
                return Optional.of(text.toString());
            }
        }
        return Optional.empty();
    }

    private static Optional<String> metricName(Map<String, Object> row) {
        for (String field : List.of("metric", "metricName", "name", "series")) {
            Object value = getIgnoreCase(row, field);
            if (value instanceof CharSequence text && !text.toString().isBlank()) {
                return Optional.of(text.toString());
            }
        }
        return Optional.empty();
    }

    private static Optional<String> infrastructureName(Map<String, Object> row) {
        for (String field : List.of(
                "host", "hostName", "host_name", "hostname", "node", "instance",
                "pod", "podName", "pod_name", "container", "containerName", "container_name", "name"
        )) {
            Object value = getIgnoreCase(row, field);
            if (value instanceof CharSequence text && !text.toString().isBlank()) {
                return Optional.of(text.toString());
            }
        }
        return Optional.empty();
    }

    private static boolean metricLooksAbnormal(Map<String, Object> row) {
        Object status = firstPresent(row, "status", "state", "level");
        if (status instanceof CharSequence text) {
            String normalized = text.toString().trim().toLowerCase(Locale.ROOT);
            if (normalized.contains("anomaly") || normalized.contains("alert")
                    || normalized.contains("warning") || normalized.contains("critical")) {
                return true;
            }
        }
        Object value = firstPresent(row, "value", "max", "p95", "p99");
        if (value instanceof Number number) {
            String metricName = metricName(row).orElse("").toLowerCase(Locale.ROOT);
            boolean alertLikeMetric = metricName.contains("error")
                    || metricName.contains("5xx")
                    || metricName.contains("timeout")
                    || metricName.contains("latency")
                    || metricName.contains("p95")
                    || metricName.contains("p99")
                    || metricName.contains("saturation");
            return alertLikeMetric && Double.isFinite(number.doubleValue()) && Math.abs(number.doubleValue()) > 0;
        }
        return false;
    }

    private static Optional<String> metricHint(Map<String, Object> row) {
        Optional<String> name = metricName(row);
        Object status = firstPresent(row, "status", "state", "level");
        Object value = firstPresent(row, "value", "max", "p95", "p99");
        if (name.isPresent()) {
            String suffix = value == null ? "" : "=" + value;
            String statusText = status == null ? "" : " status=" + status;
            return Optional.of(name.get() + suffix + statusText);
        }
        if (status != null || value != null) {
            return Optional.of("metric value=" + value + " status=" + status);
        }
        return Optional.empty();
    }

    private static boolean infrastructureLooksAbnormal(Map<String, Object> row) {
        Object status = firstPresent(row, "status", "state", "phase", "ready");
        if (status instanceof Boolean b) return !b;
        if (status instanceof CharSequence text) {
            String normalized = text.toString().trim().toLowerCase(Locale.ROOT);
            if (List.of("true", "ready", "running", "ok", "healthy", "up", "active").contains(normalized)) {
                return false;
            }
            if (normalized.contains("fail")
                    || normalized.contains("error")
                    || normalized.contains("crash")
                    || normalized.contains("oom")
                    || normalized.contains("notready")
                    || normalized.contains("not_ready")
                    || normalized.contains("down")
                    || normalized.contains("high")
                    || normalized.contains("unhealthy")
                    || normalized.contains("terminated")
                    || normalized.contains("pending")) {
                return true;
            }
        }
        Integer restarts = integer(firstPresent(row, "restartCount", "restart_count", "restarts"));
        return restarts != null && restarts > 0 || resourcePressureSignal(row).isPresent();
    }

    private static Optional<String> infrastructureState(Map<String, Object> row) {
        Optional<String> name = infrastructureName(row);
        Object status = firstPresent(row, "status", "state", "phase", "ready");
        Object restarts = firstPresent(row, "restartCount", "restart_count", "restarts");
        Object reason = firstPresent(row, "reason", "message", "lastState");
        String prefix = name.map(value -> value + " ").orElse("");
        if (status != null || restarts != null || reason != null) {
            return Optional.of(prefix + "status=" + status + " restarts=" + restarts + " reason=" + reason);
        }
        return Optional.empty();
    }

    private static Optional<String> infrastructureRestartSignal(Map<String, Object> row) {
        Integer restarts = integer(firstPresent(row, "restartCount", "restart_count", "restarts"));
        if (restarts == null || restarts <= 0) return Optional.empty();
        String prefix = infrastructureName(row).map(value -> value + " ").orElse("");
        Object reason = firstPresent(row, "reason", "message", "lastState", "last_state");
        String reasonText = reason == null ? "" : " reason=" + reason;
        return Optional.of(prefix + "restarts=" + restarts + reasonText);
    }

    private static Optional<String> resourcePressureSignal(Map<String, Object> row) {
        List<String> parts = new ArrayList<>();
        addPercentPressure(parts, row, 80.0, "cpu_usage", "cpuUsage", "cpu_usage_percent", "cpu_used_percent", "cpu_percent", "cpu");
        addPercentPressure(parts, row, 85.0, "mem_used_percent", "memory_used_percent", "memory_usage_percent", "mem_usage_percent");
        addPercentPressure(parts, row, 85.0, "disk_used_percent", "disk_usage_percent");
        addLoadPressure(parts, row, 5.0, "load", "load1", "load_1m");
        if (parts.isEmpty()) return Optional.empty();
        String prefix = infrastructureName(row).map(value -> value + " ").orElse("");
        return Optional.of(prefix + String.join(" ", parts));
    }

    private static void addPercentPressure(List<String> parts,
                                           Map<String, Object> row,
                                           double threshold,
                                           String... fields) {
        for (String field : fields) {
            Double raw = doubleValue(getIgnoreCase(row, field));
            if (raw == null) continue;
            double percent = raw <= 1.0d ? raw * 100.0d : raw;
            if (percent >= threshold) {
                parts.add(field + "=" + roundRatio(percent) + "%");
            }
            return;
        }
    }

    private static void addLoadPressure(List<String> parts,
                                        Map<String, Object> row,
                                        double threshold,
                                        String... fields) {
        for (String field : fields) {
            Double raw = doubleValue(getIgnoreCase(row, field));
            if (raw == null) continue;
            if (raw >= threshold) {
                parts.add(field + "=" + roundRatio(raw));
            }
            return;
        }
    }

    private static List<String> infrastructureDiagnosisSignals(Map<String, Object> summary, String evidenceType) {
        List<String> signals = new ArrayList<>();
        Object recordCount = summary.get("recordCount");
        Object abnormalCount = summary.get("abnormalCount");
        if (recordCount != null) signals.add(infrastructureLabel(evidenceType) + "样本=" + recordCount);
        if (abnormalCount != null && !"0".equals(String.valueOf(abnormalCount))) {
            signals.add("异常对象=" + abnormalCount + "/" + recordCount);
        }
        List<String> resourcePressure = stringList(summary.get("resourcePressure"));
        if (!resourcePressure.isEmpty()) signals.add("资源压力=" + resourcePressure);
        List<String> restartObjects = stringList(summary.get("restartObjects"));
        if (!restartObjects.isEmpty()) signals.add("重启对象=" + restartObjects);
        List<String> abnormalStates = stringList(summary.get("abnormalStates"));
        if (!abnormalStates.isEmpty()) signals.add("异常状态=" + abnormalStates);
        if (signals.isEmpty()) signals.add(infrastructureLabel(evidenceType) + "未发现明显异常");
        return signals.stream().limit(8).toList();
    }

    private static String infrastructureConclusion(Map<String, Object> summary,
                                                   String evidenceType,
                                                   int rowCount,
                                                   int abnormalCount) {
        String label = infrastructureLabel(evidenceType);
        if (rowCount == 0) {
            return "未解析到观测云" + label + "明细，不能用基础设施证据判断运行环境影响。";
        }
        List<String> unhealthyObjects = stringList(summary.get("unhealthyObjects"));
        List<String> resourcePressure = stringList(summary.get("resourcePressure"));
        List<String> restartObjects = stringList(summary.get("restartObjects"));
        if (abnormalCount > 0) {
            String objectText = unhealthyObjects.isEmpty() ? "" : "，异常对象 " + unhealthyObjects;
            String pressureText = resourcePressure.isEmpty() ? "" : "，资源压力 " + resourcePressure;
            String restartText = restartObjects.isEmpty() ? "" : "，重启对象 " + restartObjects;
            return "观测云" + label + "发现 " + abnormalCount + " 条异常记录"
                    + objectText + pressureText + restartText
                    + "；该证据支持运行环境或基础设施异常判断。";
        }
        return "观测云" + label + "样本未发现明显异常；若告警仍存在，应继续检查应用日志、发布变更和上游链路。";
    }

    private static String infrastructureLabel(String evidenceType) {
        return switch (evidenceType == null ? "" : evidenceType.toLowerCase(Locale.ROOT)) {
            case "host" -> "主机";
            case "container" -> "容器";
            case "k8s" -> "K8s/容器";
            default -> "基础设施";
        };
    }

    private static boolean rollbackAvailable(Map<String, Object> row) {
        Object value = getIgnoreCase(row, "rollbackAvailable");
        if (value instanceof Boolean b) return b;
        if (value instanceof CharSequence text) {
            String normalized = text.toString().trim().toLowerCase(Locale.ROOT);
            return "true".equals(normalized) || "yes".equals(normalized) || "available".equals(normalized);
        }
        return false;
    }

    private static boolean syntheticFailed(Map<String, Object> row) {
        Object success = firstPresent(row, "success", "available", "ok", "passed");
        if (success instanceof Boolean b) return !b;
        if (success instanceof CharSequence text) {
            String normalized = text.toString().trim().toLowerCase(Locale.ROOT);
            if (List.of("true", "yes", "ok", "success", "passed", "up", "available").contains(normalized)) return false;
            if (List.of("false", "no", "fail", "failed", "down", "unavailable").contains(normalized)) return true;
        }
        Object status = firstPresent(row, "status", "state", "result");
        if (status instanceof CharSequence text) {
            String normalized = text.toString().trim().toLowerCase(Locale.ROOT);
            return normalized.contains("fail")
                    || normalized.contains("error")
                    || normalized.contains("timeout")
                    || normalized.contains("down")
                    || normalized.contains("unavailable");
        }
        Integer code = integer(firstPresent(row, "statusCode", "status_code", "httpStatus", "http_status", "code"));
        return code != null && code >= 400;
    }

    private static Optional<String> failureReason(Map<String, Object> row) {
        for (String field : List.of("failureReason", "error", "message", "reason", "status")) {
            Object value = getIgnoreCase(row, field);
            if (value instanceof CharSequence text && !text.toString().isBlank()) {
                return Optional.of(text.toString());
            }
        }
        return Optional.empty();
    }

    private static Optional<String> syntheticName(Map<String, Object> row) {
        for (String field : List.of("checkName", "name", "taskName", "task_name", "dialTaskName", "dial_task_name")) {
            Object value = getIgnoreCase(row, field);
            if (value instanceof CharSequence text && !text.toString().isBlank()) {
                return Optional.of(text.toString());
            }
        }
        return Optional.empty();
    }

    private static Optional<String> url(Map<String, Object> row) {
        for (String field : List.of("url", "endpoint", "target", "address")) {
            Object value = getIgnoreCase(row, field);
            if (value instanceof CharSequence text && !text.toString().isBlank()) {
                return Optional.of(text.toString());
            }
        }
        return Optional.empty();
    }

    private static Optional<Integer> statusCode(Map<String, Object> row) {
        Integer code = integer(firstPresent(row, "statusCode", "status_code", "httpStatus", "http_status", "code"));
        return code == null ? Optional.empty() : Optional.of(code);
    }

    private static Optional<String> region(Map<String, Object> row) {
        for (String field : List.of("region", "location", "node", "area", "probe", "city")) {
            Object value = getIgnoreCase(row, field);
            if (value instanceof CharSequence text && !text.toString().isBlank()) {
                return Optional.of(text.toString());
            }
        }
        return Optional.empty();
    }

    private static Optional<String> node(Map<String, Object> row) {
        for (String field : List.of("node", "probe", "region", "location", "city", "name")) {
            Object value = getIgnoreCase(row, field);
            if (value instanceof CharSequence text && !text.toString().isBlank()) {
                return Optional.of(text.toString());
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static List<String> syntheticsDiagnosisSignals(Map<String, Object> summary, long failedCount) {
        List<String> signals = new ArrayList<>();
        Object checkCount = summary.get("checkCount");
        Object failureRate = summary.get("failureRate");
        if (checkCount != null) signals.add("拨测样本=" + checkCount);
        if (failureRate != null) signals.add("失败率=" + failureRate + "%");
        List<Integer> failedStatusCodes = summary.get("failedStatusCodes") instanceof List<?> list
                ? (List<Integer>) list
                : List.of();
        if (!failedStatusCodes.isEmpty()) signals.add("失败状态码=" + failedStatusCodes);
        List<String> regions = summary.get("affectedRegions") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();
        if (!regions.isEmpty()) signals.add("受影响区域=" + regions);
        List<String> nodes = summary.get("affectedNodes") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();
        if (!nodes.isEmpty()) signals.add("受影响节点=" + nodes);
        List<String> reasons = summary.get("failureReasons") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();
        if (!reasons.isEmpty()) signals.add("失败原因=" + reasons);
        if (failedCount == 0 && signals.isEmpty()) signals.add("拨测未发现失败");
        return signals.stream().limit(8).toList();
    }

    @SuppressWarnings("unchecked")
    private static String syntheticsConclusion(Map<String, Object> summary, int rowCount, long failedCount) {
        if (rowCount == 0) {
            return "未解析到观测云拨测明细，不能用拨测证据判断外部入口影响。";
        }
        List<Integer> failedStatusCodes = summary.get("failedStatusCodes") instanceof List<?> list
                ? (List<Integer>) list
                : List.of();
        List<String> regions = summary.get("affectedRegions") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();
        if (failedCount > 0) {
            String codes = failedStatusCodes.isEmpty() ? "" : "，失败状态码 " + failedStatusCodes;
            String regionText = regions.isEmpty() ? "" : "，受影响区域/节点 " + regions;
            return "观测云拨测发现 " + failedCount + " 条失败" + codes + regionText
                    + "；该证据支持外部入口或可用性受影响判断。";
        }
        return "观测云拨测样本未发现失败；若告警仍存在，应继续检查内部调用链、局部实例或告警规则。";
    }

    private static double roundRatio(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static Object firstPresent(Map<String, Object> row, String... fields) {
        for (String field : fields) {
            Object value = getIgnoreCase(row, field);
            if (value != null) return value;
        }
        return null;
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value instanceof CharSequence text) {
            try {
                return Integer.parseInt(text.toString().trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Double doubleValue(Object value) {
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof CharSequence text) {
            try {
                return Double.parseDouble(text.toString().trim().replace("%", ""));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream()
                .filter(item -> item != null && !String.valueOf(item).isBlank())
                .map(String::valueOf)
                .toList();
    }

    private static String signature(String value) {
        return abbreviate(value
                .replaceAll("\\b[0-9a-fA-F]{12,}\\b", "<id>")
                .replaceAll("\\b\\d+\\b", "<num>")
                .replaceAll("\\s+", " ")
                .trim(), 240);
    }

    private static String abbreviate(String value, int maxChars) {
        if (value == null) return "";
        if (value.length() <= maxChars) return value;
        return value.substring(0, Math.max(0, maxChars)) + "...";
    }
}
