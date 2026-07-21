package vip.mate.troubleshooting.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import vip.mate.troubleshooting.dto.SopRouteRequest;
import vip.mate.troubleshooting.model.TroubleshootingQueryTemplateEntity;
import vip.mate.troubleshooting.service.TroubleshootingConnectorConfigService;
import vip.mate.troubleshooting.service.TroubleshootingQueryTemplateService;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
public class GuanceMetricsConnector extends GuanceEvidenceSupport {

    private final TroubleshootingQueryTemplateService queryTemplateService;

    public GuanceMetricsConnector(TroubleshootingEvidenceProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, null, null);
    }

    public GuanceMetricsConnector(TroubleshootingEvidenceProperties properties,
                                  ObjectMapper objectMapper,
                                  TroubleshootingQueryTemplateService queryTemplateService) {
        this(properties, objectMapper, queryTemplateService, null);
    }

    @Autowired
    public GuanceMetricsConnector(TroubleshootingEvidenceProperties properties,
                                  ObjectMapper objectMapper,
                                  TroubleshootingQueryTemplateService queryTemplateService,
                                  TroubleshootingConnectorConfigService connectorConfigService) {
        super(properties, objectMapper, connectorConfigService);
        this.queryTemplateService = queryTemplateService;
    }

    @Override
    public boolean supports(String evidenceType) {
        return "metrics".equalsIgnoreCase(evidenceType) && enabled();
    }

    @Override
    public List<CollectedEvidence> collect(EvidenceCollectionRequest request) {
        TroubleshootingEvidenceProperties.Guance cfg = guanceConfig(request.workspaceId());
        URI uri = queryUri(cfg, cfg.getMetricsPath(), "/api/v1/df/query_data_v1");
        Map<String, Object> payloadContext = basePayloadContext(
                request,
                "metrics",
                cfg.getMetricsWindow(),
                cfg.getMetricsLimit()
        );
        payloadContext.put("includeSeries", true);
        payloadContext.put("includeAggregates", true);
        payloadContext.put("metricHints", metricHints(request.alert()));
        String metricName = firstNonBlank(
                request.alert() == null ? "" : request.alert().metricName(),
                labelValue(request.alert(), List.of("metricName", "metric", "指标"))
        );
        if (metricName.isBlank()) metricName = "request_count";
        String serviceName = firstNonBlank(
                request.alert() == null ? "" : request.alert().serviceName(),
                labelValue(request.alert(), List.of("serviceName", "service", "app", "appName", "application"))
        );
        payloadContext.put("metricNameOrDefault", metricName);
        payloadContext.put("metricNameIdentifier", metricName.replace("`", ""));
        payloadContext.put("serviceNameDql", escapeDqlString(serviceName));
        String requestedTemplateName = payloadTemplateName(request.alert());
        TroubleshootingQueryTemplateEntity dbTemplate = resolveDbTemplate(
                request.workspaceId(),
                requestedTemplateName,
                request.alert()
        );
        String resolvedTemplateName = !requestedTemplateName.isBlank()
                ? requestedTemplateName
                : dbTemplate == null ? "" : dbTemplate.getTemplateKey();
        payloadContext.put("payloadTemplateName", resolvedTemplateName);
        payloadContext.put("queryTemplateId", dbTemplate == null ? null : dbTemplate.getId());
        payloadContext.put("queryTemplateSource", dbTemplate == null ? "configuration" : "database");
        payloadContext.put("dqlQuery", dqlQuery(request.alert(), payloadContext, dbTemplate));
        Object payload = null;
        long started = System.nanoTime();
        try {
            payload = buildPayload(
                    payloadTemplate(request.alert(), cfg, dbTemplate),
                    payloadContext,
                    "Invalid Guance metrics payload template JSON"
            );
            String body = postJson(cfg, uri, payload);

            long durationMs = (System.nanoTime() - started) / 1_000_000L;
            String preview = abbreviate(redact(body), Math.max(256, cfg.getMaxResponseChars()));
            Map<String, Object> normalized = EvidenceResponseNormalizer.metrics(objectMapper, body, this::redact);
            Map<String, Object> content = content(
                    id(),
                    "collected",
                    "guance-metrics",
                    uri,
                    durationMs,
                    payloadContext,
                    payload,
                    preview
            );
            content.put("responsePreview", preview);
            content.put("normalized", normalized);
            return List.of(new CollectedEvidence(
                    "metrics",
                    id(),
                    "collected",
                    "Guance metrics snapshot",
                    metricsSummary(request.alert(), normalized),
                    content
            ));
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - started) / 1_000_000L;
            String message = abbreviate(redact(e.getMessage()), 600);
            log.warn("[TroubleshootingEvidence] Guance metrics collection failed: {}", message);
            Map<String, Object> content = content(
                    id(),
                    "unavailable",
                    "guance-metrics",
                    uri,
                    durationMs,
                    payloadContext,
                    payload,
                    message
            );
            content.put("error", message);
            return List.of(new CollectedEvidence(
                    "metrics",
                    id(),
                    "unavailable",
                    "Guance metrics unavailable",
                    "Guance metrics connector unavailable for " + target(request.alert()) + ": " + message,
                    content
            ));
        }
    }

    @Override
    public int order() {
        return 80;
    }

    @Override
    public String id() {
        return "guance-metrics";
    }

    private List<String> metricHints(SopRouteRequest alert) {
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        if (alert != null && alert.metricName() != null && !alert.metricName().isBlank()) {
            hints.add(alert.metricName());
        }
        String text = String.join(" ", List.of(
                safe(alert == null ? null : alert.alertName()),
                safe(alert == null ? null : alert.message()),
                safe(alert == null ? null : alert.rawText())
        )).toLowerCase(Locale.ROOT);
        if (text.contains("5xx") || text.contains("500") || text.contains("502")
                || text.contains("503") || text.contains("504") || text.contains("error")) {
            hints.add("http_5xx_rate");
            hints.add("error_rate");
        }
        if (text.contains("latency") || text.contains("slow") || text.contains("timeout")) {
            hints.add("p95_latency");
            hints.add("timeout_rate");
        }
        if (text.contains("traffic") || text.contains("qps") || text.contains("volume")) {
            hints.add("request_rate");
        }
        hints.add("request_count");
        return hints.stream().limit(12).toList();
    }

    private String metricsSummary(SopRouteRequest alert, Map<String, Object> normalized) {
        Object count = normalized.get("seriesCount");
        Object names = normalized.get("metricNames");
        Object hints = normalized.get("anomalyHints");
        return "Guance metrics collected " + count + " series for " + target(alert)
                + "; metricNames=" + names
                + "; anomalyHints=" + hints
                + "; sensitive fields redacted.";
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private String dqlQuery(SopRouteRequest alert,
                            Map<String, Object> context,
                            TroubleshootingQueryTemplateEntity dbTemplate) {
        Map<String, Object> labels = alert == null ? Map.of() : alert.safeLabels();
        for (String key : List.of("metricsDql", "metrics_dql", "metricsQuery", "metrics_query", "dqlQuery", "dql", "query")) {
            Object value = labels.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        for (String key : List.of("metricsDqlTemplate", "metrics_dql_template", "dqlQueryTemplate", "dql_query_template")) {
            Object value = labels.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return renderTemplate(String.valueOf(value), templateVariables(context));
            }
        }
        if (dbTemplate != null && dbTemplate.getDqlTemplate() != null && !dbTemplate.getDqlTemplate().isBlank()) {
            return renderTemplate(dbTemplate.getDqlTemplate(), templateVariables(context));
        }
        String metricName = alert == null ? "" : safe(alert.metricName());
        String serviceName = alert == null ? "" : firstNonBlank(alert.serviceName(), labelValue(alert, List.of(
                "serviceName", "service", "app", "appName", "application"
        )));
        String metric = metricName.isBlank() ? "request_count" : metricName;
        return "M::`" + metric.replace("`", "") + "`:(*) { `service` = '" + escapeDqlString(serviceName) + "' }";
    }

    private String payloadTemplate(SopRouteRequest alert,
                                   TroubleshootingEvidenceProperties.Guance cfg,
                                   TroubleshootingQueryTemplateEntity dbTemplate) {
        String inline = labelValue(alert, List.of(
                "guanceMetricsPayloadTemplate",
                "guance_metrics_payload_template",
                "metricsPayloadTemplate",
                "metrics_payload_template",
                "payloadTemplate",
                "payload_template"
        ));
        if (!inline.isBlank()) return inline;
        if (dbTemplate != null && dbTemplate.getPayloadTemplate() != null && !dbTemplate.getPayloadTemplate().isBlank()) {
            return dbTemplate.getPayloadTemplate();
        }
        if ((cfg.getMetricsPayloadTemplate() == null || cfg.getMetricsPayloadTemplate().isBlank())
                && isQueryDataPath(cfg.getMetricsPath())) {
            return DQL_QUERY_PAYLOAD_TEMPLATE;
        }
        return cfg.getMetricsPayloadTemplate();
    }

    private String payloadTemplateName(SopRouteRequest alert) {
        return labelValue(alert, List.of(
                "guanceMetricsPayloadTemplateName",
                "guance_metrics_payload_template_name",
                "metricsPayloadTemplateName",
                "metrics_payload_template_name",
                "payloadTemplateName",
                "payload_template_name"
        ));
    }

    private String labelValue(SopRouteRequest alert, List<String> keys) {
        if (alert == null) return "";
        Map<String, Object> labels = alert.safeLabels();
        for (String key : keys) {
            Object value = labels.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return "";
    }

    private TroubleshootingQueryTemplateEntity resolveDbTemplate(long workspaceId,
                                                                 String requestedTemplateName,
                                                                 SopRouteRequest alert) {
        if (queryTemplateService == null) return null;
        try {
            return queryTemplateService.resolveForAlert(workspaceId, "guance", "metrics", requestedTemplateName, alert)
                    .orElse(null);
        } catch (Exception e) {
            log.warn("[TroubleshootingEvidence] Failed to resolve Guance metrics query template: {}", e.getMessage());
            return null;
        }
    }

    private static String escapeDqlString(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first.trim() : second == null ? "" : second.trim();
    }
}
