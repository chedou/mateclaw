package vip.mate.troubleshooting.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import vip.mate.troubleshooting.dto.SopRouteRequest;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Slf4j
@Component
public class LogSearchConnector implements EvidenceConnector {

    private static final Pattern SECRET_VALUE = Pattern.compile(
            "(?i)(\"?\\b(?:authorization|cookie|set-cookie|token|access_token|refresh_token|password|passwd|secret|api[_-]?key)\\b\"?)\\s*[:=]\\s*\"?([^\"\\s,;&}]+)\"?"
    );
    private static final Pattern BEARER = Pattern.compile("(?i)bearer\\s+[A-Za-z0-9._~+/-]+");
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"
    );
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)"
    );
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z0-9_.-]+)}");

    private final TroubleshootingEvidenceProperties properties;
    private final ObjectMapper objectMapper;
    private volatile RestClient restClient;

    public LogSearchConnector(TroubleshootingEvidenceProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String evidenceType) {
        TroubleshootingEvidenceProperties.LogSearch cfg = properties.getLogSearch();
        return "logs".equalsIgnoreCase(evidenceType)
                && cfg.isEnabled()
                && cfg.getBaseUrl() != null
                && !cfg.getBaseUrl().isBlank();
    }

    @Override
    public List<CollectedEvidence> collect(EvidenceCollectionRequest request) {
        TroubleshootingEvidenceProperties.LogSearch cfg = properties.getLogSearch();
        URI uri = queryUri(cfg);
        Map<String, Object> payloadContext = buildPayloadContext(request, cfg);
        Object payload = null;
        long started = System.nanoTime();
        try {
            payload = buildPayload(request, cfg, payloadContext);
            String body = restClient(cfg)
                    .post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        if (cfg.getToken() != null && !cfg.getToken().isBlank()) {
                            headers.set(
                                    value(cfg.getTokenHeader(), "Authorization"),
                                    value(cfg.getTokenPrefix(), "") + cfg.getToken().trim()
                            );
                        }
                    })
                    .body(payload)
                    .retrieve()
                    .body(String.class);

            long durationMs = (System.nanoTime() - started) / 1_000_000L;
            String preview = abbreviate(redact(body), Math.max(256, cfg.getMaxResponseChars()));
            Map<String, Object> normalized = EvidenceResponseNormalizer.logs(objectMapper, body, this::redact);
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("connector", id());
            content.put("status", "collected");
            content.put("mode", "log-search");
            content.put("endpoint", uri.toString());
            content.put("durationMs", durationMs);
            content.put("timeWindow", payloadContext.get("window"));
            content.put("query", payload);
            content.put("rawPreview", preview);
            content.put("request", payload);
            content.put("responsePreview", preview);
            content.put("normalized", normalized);
            content.put("redacted", true);
            return List.of(new CollectedEvidence(
                    "logs",
                    id(),
                    "collected",
                    "LogSearch log sample",
                    logSummary(request.alert(), normalized),
                    content
            ));
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - started) / 1_000_000L;
            String message = abbreviate(redact(e.getMessage()), 600);
            log.warn("[TroubleshootingEvidence] LogSearch collection failed: {}", message);
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("connector", id());
            content.put("status", "unavailable");
            content.put("mode", "log-search");
            content.put("endpoint", uri.toString());
            content.put("durationMs", durationMs);
            content.put("timeWindow", payloadContext.get("window"));
            content.put("query", payload == null ? payloadContext : payload);
            content.put("rawPreview", message);
            content.put("request", payload == null ? payloadContext : payload);
            content.put("error", message);
            content.put("redacted", true);
            return List.of(new CollectedEvidence(
                    "logs",
                    id(),
                    "unavailable",
                    "LogSearch unavailable",
                    "LogSearch connector unavailable for " + target(request.alert()) + ": " + message,
                    content
            ));
        }
    }

    @Override
    public int order() {
        return 110;
    }

    @Override
    public String id() {
        return "logsearch-logs";
    }

    RestClient restClient(TroubleshootingEvidenceProperties.LogSearch cfg) {
        RestClient existing = this.restClient;
        if (existing != null) return existing;
        synchronized (this) {
            if (this.restClient != null) return this.restClient;
            JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                    HttpClient.newBuilder()
                            .connectTimeout(cfg.getConnectTimeout())
                            .build()
            );
            requestFactory.setReadTimeout(cfg.getReadTimeout());
            this.restClient = RestClient.builder()
                    .requestFactory(requestFactory)
                    .build();
            return this.restClient;
        }
    }

    private URI queryUri(TroubleshootingEvidenceProperties.LogSearch cfg) {
        String base = cfg.getBaseUrl().trim().replaceAll("/+$", "");
        String path = value(cfg.getQueryPath(), "/api/v1/search");
        if (!path.startsWith("/")) path = "/" + path;
        return URI.create(base + path);
    }

    private Object buildPayload(EvidenceCollectionRequest request,
                                TroubleshootingEvidenceProperties.LogSearch cfg,
                                Map<String, Object> context) {
        if (cfg.getPayloadTemplate() == null || cfg.getPayloadTemplate().isBlank()) {
            return context;
        }
        String rendered = renderTemplate(cfg.getPayloadTemplate(), templateVariables(context));
        try {
            return objectMapper.readValue(rendered, Object.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid LogSearch payload template JSON: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> buildPayloadContext(EvidenceCollectionRequest request,
                                                    TroubleshootingEvidenceProperties.LogSearch cfg) {
        SopRouteRequest alert = request.alert();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("caseId", request.caseId());
        payload.put("runId", request.run() == null ? null : request.run().getId());
        payload.put("domain", request.sop() == null ? null : request.sop().domain());
        payload.put("scenario", request.sop() == null ? null : request.sop().scenario());
        payload.put("evidenceType", request.evidenceType());
        payload.put("window", value(cfg.getWindow(), "alert_time +/- 15m"));
        payload.put("limit", Math.max(1, cfg.getLimit()));
        payload.put("fields", List.of("timestamp", "level", "message", "traceId", "pod", "instance"));
        payload.put("collectedAt", LocalDateTime.now().toString());
        if (alert != null) {
            payload.put("eventId", alert.eventId());
            payload.put("severity", alert.severity());
            payload.put("serviceName", alert.serviceName());
            payload.put("env", alert.env());
            payload.put("cluster", alert.cluster());
            payload.put("namespace", alert.namespace());
            payload.put("pod", alert.pod());
            payload.put("instance", alert.instance());
            payload.put("endpoint", alert.endpoint());
            payload.put("metricName", alert.metricName());
            payload.put("keywords", keywords(alert));
            payload.put("labels", alert.safeLabels());
        }
        return payload;
    }

    private Map<String, String> templateVariables(Map<String, Object> context) {
        Map<String, String> variables = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : context.entrySet()) {
            variables.put(entry.getKey(), valueForTemplate(entry.getValue()));
        }
        Object labels = context.get("labels");
        if (labels instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                variables.put("label." + entry.getKey(), valueForTemplate(entry.getValue()));
            }
        }
        return variables;
    }

    private String renderTemplate(String template, Map<String, String> variables) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String replacement = escapeJsonString(variables.getOrDefault(matcher.group(1), ""));
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private String valueForTemplate(Object value) {
        if (value == null) return "";
        if (value instanceof List<?> list) {
            return String.join(" ", list.stream()
                    .map(item -> item == null ? "" : String.valueOf(item))
                    .filter(item -> !item.isBlank())
                    .toList());
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .filter(item -> !item.isBlank())
                    .reduce((left, right) -> left + " " + right)
                    .orElse("");
        }
        return String.valueOf(value);
    }

    private String escapeJsonString(String value) {
        try {
            String json = objectMapper.writeValueAsString(value == null ? "" : value);
            return json.length() >= 2 ? json.substring(1, json.length() - 1) : "";
        } catch (Exception e) {
            return value == null ? "" : value
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"");
        }
    }

    private List<String> keywords(SopRouteRequest alert) {
        return Stream.of(
                        alert.alertName(),
                        alert.message(),
                        alert.rawText(),
                        alert.metricName(),
                        alert.endpoint()
                )
                .filter(v -> v != null && !v.isBlank())
                .map(v -> abbreviate(v.trim(), 160).toLowerCase(Locale.ROOT))
                .distinct()
                .limit(8)
                .toList();
    }

    private String target(SopRouteRequest alert) {
        if (alert == null) return "unknown target";
        return String.join(" / ", List.of(
                value(alert.serviceName(), "unknown-service"),
                value(alert.env(), "unknown-env"),
                value(alert.cluster(), "unknown-cluster"),
                value(alert.endpoint(), "unknown-endpoint")
        ));
    }

    String redact(String input) {
        if (input == null || input.isBlank()) return input;
        String redacted = SECRET_VALUE.matcher(input).replaceAll("$1=<redacted>");
        redacted = BEARER.matcher(redacted).replaceAll("Bearer <redacted>");
        redacted = EMAIL.matcher(redacted).replaceAll("<email-redacted>");
        return PHONE.matcher(redacted).replaceAll("<phone-redacted>");
    }

    String payloadJsonForTest(EvidenceCollectionRequest request) {
        try {
            TroubleshootingEvidenceProperties.LogSearch cfg = properties.getLogSearch();
            Map<String, Object> context = buildPayloadContext(request, cfg);
            return objectMapper.writeValueAsString(buildPayload(request, cfg, context));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String logSummary(SopRouteRequest alert, Map<String, Object> normalized) {
        Object count = normalized.get("matchedCount");
        Object signatures = normalized.get("errorSignatures");
        String suffix = signatures instanceof List<?> list && !list.isEmpty()
                ? "; top signature: " + list.get(0)
                : "";
        return "LogSearch collected " + count + " log matches for " + target(alert)
                + suffix + "; sensitive fields redacted.";
    }

    private static String abbreviate(String value, int maxChars) {
        if (value == null) return "";
        if (value.length() <= maxChars) return value;
        return value.substring(0, Math.max(0, maxChars)) + "...";
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
