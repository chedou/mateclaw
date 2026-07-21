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
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
public class GuanceSyntheticsConnector extends GuanceEvidenceSupport {

    private final TroubleshootingQueryTemplateService queryTemplateService;

    public GuanceSyntheticsConnector(TroubleshootingEvidenceProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, null, null);
    }

    public GuanceSyntheticsConnector(TroubleshootingEvidenceProperties properties,
                                     ObjectMapper objectMapper,
                                     TroubleshootingQueryTemplateService queryTemplateService) {
        this(properties, objectMapper, queryTemplateService, null);
    }

    @Autowired
    public GuanceSyntheticsConnector(TroubleshootingEvidenceProperties properties,
                                     ObjectMapper objectMapper,
                                     TroubleshootingQueryTemplateService queryTemplateService,
                                     TroubleshootingConnectorConfigService connectorConfigService) {
        super(properties, objectMapper, connectorConfigService);
        this.queryTemplateService = queryTemplateService;
    }

    @Override
    public boolean supports(String evidenceType) {
        return isSynthetics(evidenceType) && enabled();
    }

    @Override
    public List<CollectedEvidence> collect(EvidenceCollectionRequest request) {
        TroubleshootingEvidenceProperties.Guance cfg = guanceConfig(request.workspaceId());
        URI uri = queryUri(cfg, cfg.getSyntheticsPath(), "/api/v1/synthetics/search");
        Map<String, Object> payloadContext = basePayloadContext(
                request,
                "synthetics",
                cfg.getWindow(),
                cfg.getLimit()
        );
        payloadContext.put("includeFailures", true);
        payloadContext.put("includeRegions", true);
        String taskName = syntheticsTaskName(request.alert());
        String requestedTemplateName = payloadTemplateName(request.alert());
        TroubleshootingQueryTemplateEntity dbTemplate = resolveDbTemplate(
                request.workspaceId(),
                requestedTemplateName,
                request.alert()
        );
        String resolvedTemplateName = !requestedTemplateName.isBlank()
                ? requestedTemplateName
                : dbTemplate == null ? "" : dbTemplate.getTemplateKey();
        payloadContext.put("syntheticsTaskName", taskName);
        payloadContext.put("syntheticsTaskNameDql", escapeDqlString(taskName));
        payloadContext.put("dialTaskName", taskName);
        payloadContext.put("dialTaskNameDql", escapeDqlString(taskName));
        payloadContext.put("taskName", taskName);
        payloadContext.put("taskNameDql", escapeDqlString(taskName));
        payloadContext.put("payloadTemplateName", resolvedTemplateName);
        payloadContext.put("queryTemplateId", dbTemplate == null ? null : dbTemplate.getId());
        payloadContext.put("queryTemplateSource", dbTemplate == null ? "configuration" : "database");
        payloadContext.put("dqlQuery", dqlQuery(request.alert(), taskName, payloadContext, dbTemplate));
        Object payload = null;
        long started = System.nanoTime();
        try {
            payload = buildPayload(
                    payloadTemplate(request.alert(), cfg, dbTemplate),
                    payloadContext,
                    "Invalid Guance synthetics payload template JSON"
            );
            String body = postJson(cfg, uri, payload);

            long durationMs = (System.nanoTime() - started) / 1_000_000L;
            String preview = abbreviate(redact(body), Math.max(256, cfg.getMaxResponseChars()));
            Map<String, Object> normalized = EvidenceResponseNormalizer.synthetics(objectMapper, body, this::redact);
            Map<String, Object> content = content(
                    id(),
                    "collected",
                    "guance-synthetics",
                    uri,
                    durationMs,
                    payloadContext,
                    payload,
                    preview
            );
            content.put("responsePreview", preview);
            content.put("normalized", normalized);
            return List.of(new CollectedEvidence(
                    "synthetics",
                    id(),
                    "collected",
                    "Guance synthetics checks",
                    syntheticsSummary(request.alert(), normalized),
                    content
            ));
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - started) / 1_000_000L;
            String message = abbreviate(redact(e.getMessage()), 600);
            log.warn("[TroubleshootingEvidence] Guance synthetics collection failed: {}", message);
            Map<String, Object> content = content(
                    id(),
                    "unavailable",
                    "guance-synthetics",
                    uri,
                    durationMs,
                    payloadContext,
                    payload,
                    message
            );
            content.put("error", message);
            return List.of(new CollectedEvidence(
                    "synthetics",
                    id(),
                    "unavailable",
                    "Guance synthetics unavailable",
                    "Guance synthetics connector unavailable for " + target(request.alert()) + ": " + message,
                    content
            ));
        }
    }

    @Override
    public int order() {
        return 90;
    }

    @Override
    public String id() {
        return "guance-synthetics";
    }

    private String syntheticsSummary(SopRouteRequest alert, Map<String, Object> normalized) {
        Object count = normalized.get("checkCount");
        Object failed = normalized.get("failedCount");
        Object regions = normalized.get("affectedRegions");
        return "Guance synthetics collected " + count + " checks for " + target(alert)
                + "; failedCount=" + failed
                + "; affectedRegions=" + regions
                + "; sensitive fields redacted.";
    }

    private String syntheticsTaskName(SopRouteRequest alert) {
        if (alert != null) {
            Map<String, Object> labels = alert.safeLabels();
            for (String key : List.of(
                    "syntheticsTaskName",
                    "synthetics_task_name",
                    "dialTaskName",
                    "dial_task_name",
                    "taskName",
                    "task_name",
                    "checkName",
                    "check_name",
                    "availabilityTaskName",
                    "availability_task_name",
                    "name",
                    "拨测任务"
            )) {
                Object value = labels.get(key);
                if (value != null && !String.valueOf(value).isBlank()) {
                    return String.valueOf(value).trim();
                }
            }
            if (alert.alertName() != null && !alert.alertName().isBlank()) return alert.alertName().trim();
            if (alert.serviceName() != null && !alert.serviceName().isBlank()) return alert.serviceName().trim();
        }
        return "";
    }

    private String dqlQuery(SopRouteRequest alert,
                            String taskName,
                            Map<String, Object> context,
                            TroubleshootingQueryTemplateEntity dbTemplate) {
        Map<String, Object> labels = alert == null ? Map.of() : alert.safeLabels();
        for (String key : List.of("dqlQuery", "dql", "query", "syntheticsDql", "synthetics_dql")) {
            Object value = labels.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        for (String key : List.of("dqlQueryTemplate", "dql_query_template", "syntheticsDqlTemplate", "synthetics_dql_template")) {
            Object value = labels.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return renderTemplate(String.valueOf(value), templateVariables(context));
            }
        }
        if (dbTemplate != null && dbTemplate.getDqlTemplate() != null && !dbTemplate.getDqlTemplate().isBlank()) {
            return renderTemplate(dbTemplate.getDqlTemplate(), templateVariables(context));
        }
        return "D::http_dial_testing:(`status_code`, `url`, `name`) { `name` = '" + escapeDqlString(taskName) + "' }";
    }

    private String payloadTemplate(SopRouteRequest alert,
                                   TroubleshootingEvidenceProperties.Guance cfg,
                                   TroubleshootingQueryTemplateEntity dbTemplate) {
        String inline = labelValue(alert, List.of(
                "guanceSyntheticsPayloadTemplate",
                "guance_synthetics_payload_template",
                "syntheticsPayloadTemplate",
                "synthetics_payload_template",
                "dialPayloadTemplate",
                "dial_payload_template"
        ));
        if (!inline.isBlank()) return inline;

        if (dbTemplate != null && dbTemplate.getPayloadTemplate() != null && !dbTemplate.getPayloadTemplate().isBlank()) {
            return dbTemplate.getPayloadTemplate();
        }

        String name = payloadTemplateName(alert);
        if (!name.isBlank() && cfg.getSyntheticsPayloadTemplates() != null) {
            String named = cfg.getSyntheticsPayloadTemplates().get(name);
            if (named != null && !named.isBlank()) return named;
        }
        if ((cfg.getPayloadTemplate() == null || cfg.getPayloadTemplate().isBlank())
                && isQueryDataPath(cfg.getSyntheticsPath())) {
            return DQL_QUERY_PAYLOAD_TEMPLATE;
        }
        return cfg.getPayloadTemplate();
    }

    private String payloadTemplateName(SopRouteRequest alert) {
        return labelValue(alert, List.of(
                "guanceSyntheticsPayloadTemplateName",
                "guance_synthetics_payload_template_name",
                "syntheticsPayloadTemplateName",
                "synthetics_payload_template_name",
                "dialPayloadTemplateName",
                "dial_payload_template_name",
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
            return queryTemplateService.resolveForAlert(workspaceId, "guance", "synthetics", requestedTemplateName, alert)
                    .orElse(null);
        } catch (Exception e) {
            log.warn("[TroubleshootingEvidence] Failed to resolve Guance synthetics query template: {}", e.getMessage());
            return null;
        }
    }

    private static String escapeDqlString(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static boolean isSynthetics(String evidenceType) {
        if (evidenceType == null) return false;
        String normalized = evidenceType.trim().toLowerCase(Locale.ROOT);
        return List.of("synthetics", "synthetic", "dialtest", "dial-test", "dial_test", "guance-synthetics", "拨测")
                .contains(normalized);
    }
}
