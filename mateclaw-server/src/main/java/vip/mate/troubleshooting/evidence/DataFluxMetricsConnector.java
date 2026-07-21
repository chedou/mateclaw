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
import java.util.regex.Pattern;

@Slf4j
@Component
public class DataFluxMetricsConnector implements EvidenceConnector {

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

    private final TroubleshootingEvidenceProperties properties;
    private final ObjectMapper objectMapper;
    private volatile RestClient restClient;

    public DataFluxMetricsConnector(TroubleshootingEvidenceProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String evidenceType) {
        TroubleshootingEvidenceProperties.DataFlux cfg = properties.getDataflux();
        return "metrics".equalsIgnoreCase(evidenceType)
                && cfg.isEnabled()
                && cfg.getBaseUrl() != null
                && !cfg.getBaseUrl().isBlank();
    }

    @Override
    public List<CollectedEvidence> collect(EvidenceCollectionRequest request) {
        TroubleshootingEvidenceProperties.DataFlux cfg = properties.getDataflux();
        URI uri = queryUri(cfg);
        Map<String, Object> payload = buildPayload(request);
        long started = System.nanoTime();
        try {
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
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("connector", id());
            content.put("status", "collected");
            content.put("mode", "dataflux");
            content.put("endpoint", uri.toString());
            content.put("durationMs", durationMs);
            content.put("timeWindow", payload.get("window"));
            content.put("query", payload);
            content.put("rawPreview", preview);
            content.put("request", payload);
            content.put("responsePreview", preview);
            content.put("redacted", true);
            return List.of(new CollectedEvidence(
                    "metrics",
                    id(),
                    "collected",
                    "DataFlux metrics snapshot",
                    "DataFlux metrics collected for " + target(request.alert()) + "; response preview stored with sensitive fields redacted.",
                    content
            ));
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - started) / 1_000_000L;
            String message = abbreviate(redact(e.getMessage()), 600);
            log.warn("[TroubleshootingEvidence] DataFlux metrics collection failed: {}", message);
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("connector", id());
            content.put("status", "unavailable");
            content.put("mode", "dataflux");
            content.put("endpoint", uri.toString());
            content.put("durationMs", durationMs);
            content.put("timeWindow", payload.get("window"));
            content.put("query", payload);
            content.put("rawPreview", message);
            content.put("request", payload);
            content.put("error", message);
            content.put("redacted", true);
            return List.of(new CollectedEvidence(
                    "metrics",
                    id(),
                    "unavailable",
                    "DataFlux metrics unavailable",
                    "DataFlux metrics connector unavailable for " + target(request.alert()) + ": " + message,
                    content
            ));
        }
    }

    @Override
    public int order() {
        return 100;
    }

    @Override
    public String id() {
        return "dataflux-metrics";
    }

    RestClient restClient(TroubleshootingEvidenceProperties.DataFlux cfg) {
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

    private URI queryUri(TroubleshootingEvidenceProperties.DataFlux cfg) {
        String base = cfg.getBaseUrl().trim().replaceAll("/+$", "");
        String path = value(cfg.getQueryPath(), "/api/v1/query");
        if (!path.startsWith("/")) path = "/" + path;
        return URI.create(base + path);
    }

    private Map<String, Object> buildPayload(EvidenceCollectionRequest request) {
        SopRouteRequest alert = request.alert();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("caseId", request.caseId());
        payload.put("runId", request.run() == null ? null : request.run().getId());
        payload.put("domain", request.sop() == null ? null : request.sop().domain());
        payload.put("scenario", request.sop() == null ? null : request.sop().scenario());
        payload.put("evidenceType", request.evidenceType());
        payload.put("window", "alert_time +/- 15m");
        payload.put("collectedAt", LocalDateTime.now().toString());
        if (alert != null) {
            payload.put("eventId", alert.eventId());
            payload.put("severity", alert.severity());
            payload.put("serviceName", alert.serviceName());
            payload.put("env", alert.env());
            payload.put("cluster", alert.cluster());
            payload.put("namespace", alert.namespace());
            payload.put("endpoint", alert.endpoint());
            payload.put("metricName", alert.metricName());
            payload.put("labels", alert.safeLabels());
        }
        return payload;
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
            return objectMapper.writeValueAsString(buildPayload(request));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
