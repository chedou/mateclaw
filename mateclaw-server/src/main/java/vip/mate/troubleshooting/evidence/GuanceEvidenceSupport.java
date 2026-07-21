package vip.mate.troubleshooting.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import vip.mate.troubleshooting.dto.SopRouteRequest;
import vip.mate.troubleshooting.service.TroubleshootingConnectorConfigService;

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

abstract class GuanceEvidenceSupport implements EvidenceConnector {

    protected static final String DQL_QUERY_PAYLOAD_TEMPLATE = """
            {
              "queries": [
                {
                  "qtype": "dql",
                  "query": {
                    "q": "${dqlQuery}",
                    "_funcList": [],
                    "funcList": [],
                    "maxPointCount": 720,
                    "interval": 10,
                    "align_time": true,
                    "sorder_by": [],
                    "slimit": ${limit},
                    "disable_sampling": false,
                    "timeRange": [],
                    "tz": "Asia/Shanghai"
                  }
                }
              ]
            }
            """;

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

    protected final TroubleshootingEvidenceProperties properties;
    protected final ObjectMapper objectMapper;
    private final TroubleshootingConnectorConfigService connectorConfigService;
    private volatile RestClient restClient;

    protected GuanceEvidenceSupport(TroubleshootingEvidenceProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, null);
    }

    protected GuanceEvidenceSupport(TroubleshootingEvidenceProperties properties,
                                    ObjectMapper objectMapper,
                                    TroubleshootingConnectorConfigService connectorConfigService) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.connectorConfigService = connectorConfigService;
    }

    protected boolean enabled() {
        TroubleshootingEvidenceProperties.Guance cfg = properties.getGuance();
        return cfg.isEnabled() && cfg.getBaseUrl() != null && !cfg.getBaseUrl().isBlank()
                || connectorConfigService != null && connectorConfigService.hasEnabledGuanceConfig();
    }

    protected TroubleshootingEvidenceProperties.Guance guanceConfig(long workspaceId) {
        return connectorConfigService == null
                ? properties.getGuance()
                : connectorConfigService.resolveGuance(workspaceId);
    }

    protected URI queryUri(TroubleshootingEvidenceProperties.Guance cfg, String path, String fallbackPath) {
        String base = cfg.getBaseUrl().trim().replaceAll("/+$", "");
        String normalizedPath = value(path, fallbackPath);
        if (!normalizedPath.startsWith("/")) normalizedPath = "/" + normalizedPath;
        return URI.create(base + normalizedPath);
    }

    protected String postJson(TroubleshootingEvidenceProperties.Guance cfg, URI uri, Object payload) {
        return restClient(cfg)
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
    }

    protected boolean isQueryDataPath(String path) {
        String normalized = path == null ? "" : path.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("query_data");
    }

    protected Object buildPayload(String payloadTemplate,
                                  Map<String, Object> context,
                                  String invalidTemplateMessagePrefix) {
        if (payloadTemplate == null || payloadTemplate.isBlank()) {
            return context;
        }
        String rendered = renderJsonTemplate(payloadTemplate, context);
        try {
            return objectMapper.readValue(rendered, Object.class);
        } catch (Exception e) {
            throw new IllegalArgumentException(invalidTemplateMessagePrefix + ": " + e.getMessage(), e);
        }
    }

    protected Map<String, Object> basePayloadContext(EvidenceCollectionRequest request,
                                                     String evidenceType,
                                                     String window,
                                                     int limit) {
        SopRouteRequest alert = request.alert();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("caseId", request.caseId());
        payload.put("runId", request.run() == null ? null : request.run().getId());
        payload.put("domain", request.sop() == null ? null : request.sop().domain());
        payload.put("scenario", request.sop() == null ? null : request.sop().scenario());
        payload.put("evidenceType", evidenceType);
        payload.put("window", value(window, "alert_time +/- 15m"));
        payload.put("limit", Math.max(1, limit));
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

    protected Map<String, Object> content(String connectorId,
                                          String status,
                                          String mode,
                                          URI uri,
                                          long durationMs,
                                          Map<String, Object> payloadContext,
                                          Object payload,
                                          String preview) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("connector", connectorId);
        content.put("status", status);
        content.put("mode", mode);
        content.put("endpoint", uri.toString());
        content.put("durationMs", durationMs);
        content.put("timeWindow", payloadContext.get("window"));
        content.put("query", payload == null ? payloadContext : payload);
        content.put("rawPreview", preview);
        content.put("request", payload == null ? payloadContext : payload);
        content.put("redacted", true);
        return content;
    }

    protected RestClient restClient(TroubleshootingEvidenceProperties.Guance cfg) {
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

    protected Map<String, String> templateVariables(Map<String, Object> context) {
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

    protected Map<String, Object> templateValueVariables(Map<String, Object> context) {
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.putAll(context);
        Object labels = context.get("labels");
        if (labels instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                variables.put("label." + entry.getKey(), entry.getValue());
            }
        }
        return variables;
    }

    protected String renderTemplate(String template, Map<String, String> variables) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String replacement = escapeJsonString(variables.getOrDefault(matcher.group(1), ""));
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    protected String renderJsonTemplate(String template, Map<String, Object> context) {
        Map<String, String> stringVariables = templateVariables(context);
        Map<String, Object> valueVariables = templateValueVariables(context);
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = isInsideJsonString(template, matcher.start())
                    ? escapeJsonString(stringVariables.getOrDefault(key, ""))
                    : jsonLiteral(valueVariables.getOrDefault(key, stringVariables.getOrDefault(key, "")));
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    protected String valueForTemplate(Object value) {
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

    protected String escapeJsonString(String value) {
        try {
            String json = objectMapper.writeValueAsString(value == null ? "" : value);
            return json.length() >= 2 ? json.substring(1, json.length() - 1) : "";
        } catch (Exception e) {
            return value == null ? "" : value
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"");
        }
    }

    private String jsonLiteral(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? "" : value);
        } catch (Exception e) {
            return objectMapper.valueToTree(valueForTemplate(value)).toString();
        }
    }

    private static boolean isInsideJsonString(String template, int position) {
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < position; i++) {
            char ch = template.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            if (ch == '"') {
                inString = !inString;
            }
        }
        return inString;
    }

    protected List<String> keywords(SopRouteRequest alert) {
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

    protected String target(SopRouteRequest alert) {
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

    protected static String abbreviate(String value, int maxChars) {
        if (value == null) return "";
        if (value.length() <= maxChars) return value;
        return value.substring(0, Math.max(0, maxChars)) + "...";
    }

    protected static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
