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
public class GuanceInfrastructureConnector extends GuanceEvidenceSupport {

    private final TroubleshootingQueryTemplateService queryTemplateService;

    public GuanceInfrastructureConnector(TroubleshootingEvidenceProperties properties,
                                         ObjectMapper objectMapper) {
        this(properties, objectMapper, null, null);
    }

    public GuanceInfrastructureConnector(TroubleshootingEvidenceProperties properties,
                                         ObjectMapper objectMapper,
                                         TroubleshootingQueryTemplateService queryTemplateService) {
        this(properties, objectMapper, queryTemplateService, null);
    }

    @Autowired
    public GuanceInfrastructureConnector(TroubleshootingEvidenceProperties properties,
                                         ObjectMapper objectMapper,
                                         TroubleshootingQueryTemplateService queryTemplateService,
                                         TroubleshootingConnectorConfigService connectorConfigService) {
        super(properties, objectMapper, connectorConfigService);
        this.queryTemplateService = queryTemplateService;
    }

    @Override
    public boolean supports(String evidenceType) {
        return isInfrastructure(evidenceType) && enabled();
    }

    @Override
    public List<CollectedEvidence> collect(EvidenceCollectionRequest request) {
        String evidenceType = normalizeEvidenceType(request.evidenceType());
        TroubleshootingEvidenceProperties.Guance cfg = guanceConfig(request.workspaceId());
        URI uri = queryUri(cfg, cfg.getMetricsPath(), "/api/v1/df/query_data_v1");
        Map<String, Object> payloadContext = basePayloadContext(
                request,
                evidenceType,
                cfg.getMetricsWindow(),
                cfg.getMetricsLimit()
        );
        enrichInfrastructureContext(payloadContext, request.alert(), evidenceType);

        String requestedTemplateName = payloadTemplateName(request.alert(), evidenceType);
        TroubleshootingQueryTemplateEntity dbTemplate = resolveDbTemplate(
                request.workspaceId(),
                evidenceType,
                requestedTemplateName,
                request.alert()
        );
        String resolvedTemplateName = !requestedTemplateName.isBlank()
                ? requestedTemplateName
                : dbTemplate == null ? "" : dbTemplate.getTemplateKey();
        payloadContext.put("payloadTemplateName", resolvedTemplateName);
        payloadContext.put("queryTemplateId", dbTemplate == null ? null : dbTemplate.getId());
        payloadContext.put("queryTemplateSource", dbTemplate == null ? "configuration" : "database");
        payloadContext.put("dqlQuery", dqlQuery(request.alert(), evidenceType, payloadContext, dbTemplate));

        Object payload = null;
        long started = System.nanoTime();
        try {
            payload = buildPayload(
                    payloadTemplate(request.alert(), evidenceType, cfg, dbTemplate),
                    payloadContext,
                    "Invalid Guance " + evidenceType + " payload template JSON"
            );
            String body = postJson(cfg, uri, payload);

            long durationMs = (System.nanoTime() - started) / 1_000_000L;
            String preview = abbreviate(redact(body), Math.max(256, cfg.getMaxResponseChars()));
            Map<String, Object> normalized = EvidenceResponseNormalizer.infrastructure(
                    objectMapper,
                    body,
                    this::redact,
                    evidenceType
            );
            Map<String, Object> content = content(
                    id(),
                    "collected",
                    "guance-" + evidenceType,
                    uri,
                    durationMs,
                    payloadContext,
                    payload,
                    preview
            );
            content.put("responsePreview", preview);
            content.put("normalized", normalized);
            return List.of(new CollectedEvidence(
                    evidenceType,
                    id(),
                    "collected",
                    "Guance " + evidenceType + " snapshot",
                    infrastructureSummary(request.alert(), evidenceType, normalized),
                    content
            ));
        } catch (Exception e) {
            long durationMs = (System.nanoTime() - started) / 1_000_000L;
            String message = abbreviate(redact(e.getMessage()), 600);
            log.warn("[TroubleshootingEvidence] Guance {} collection failed: {}", evidenceType, message);
            Map<String, Object> content = content(
                    id(),
                    "unavailable",
                    "guance-" + evidenceType,
                    uri,
                    durationMs,
                    payloadContext,
                    payload,
                    message
            );
            content.put("error", message);
            return List.of(new CollectedEvidence(
                    evidenceType,
                    id(),
                    "unavailable",
                    "Guance " + evidenceType + " unavailable",
                    "Guance " + evidenceType + " connector unavailable for " + target(request.alert()) + ": " + message,
                    content
            ));
        }
    }

    @Override
    public int order() {
        return 85;
    }

    @Override
    public String id() {
        return "guance-infrastructure";
    }

    private void enrichInfrastructureContext(Map<String, Object> context,
                                             SopRouteRequest alert,
                                             String evidenceType) {
        String hostName = labelValue(alert, List.of(
                "hostName", "host_name", "hostname", "host", "nodeName", "node_name", "node"
        ));
        if (hostName.isBlank() && alert != null && alert.instance() != null) hostName = alert.instance().trim();
        String containerName = labelValue(alert, List.of(
                "containerName", "container_name", "container", "containerId", "container_id"
        ));
        String podName = labelValue(alert, List.of("podName", "pod_name", "pod"));
        if (podName.isBlank() && alert != null && alert.pod() != null) podName = alert.pod().trim();
        String namespace = alert == null ? "" : value(alert.namespace(), labelValue(alert, List.of(
                "namespace", "ns", "k8sNamespace", "k8s_namespace"
        )));
        String cluster = alert == null ? "" : value(alert.cluster(), labelValue(alert, List.of(
                "cluster", "clusterName", "cluster_name", "k8sCluster", "k8s_cluster"
        )));

        context.put("hostName", hostName);
        context.put("hostNameDql", escapeDqlString(hostName));
        context.put("containerName", containerName);
        context.put("containerNameDql", escapeDqlString(containerName));
        context.put("podName", podName);
        context.put("podNameDql", escapeDqlString(podName));
        context.put("namespace", namespace);
        context.put("namespaceDql", escapeDqlString(namespace));
        context.put("cluster", cluster);
        context.put("clusterDql", escapeDqlString(cluster));
        context.put("infraObjectName", switch (evidenceType) {
            case "host" -> hostName;
            case "container", "k8s" -> !podName.isBlank() ? podName : containerName;
            default -> "";
        });
    }

    private String dqlQuery(SopRouteRequest alert,
                            String evidenceType,
                            Map<String, Object> context,
                            TroubleshootingQueryTemplateEntity dbTemplate) {
        Map<String, Object> labels = alert == null ? Map.of() : alert.safeLabels();
        for (String key : dqlLabelKeys(evidenceType, false)) {
            Object value = labels.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        for (String key : dqlLabelKeys(evidenceType, true)) {
            Object value = labels.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return renderTemplate(String.valueOf(value), templateVariables(context));
            }
        }
        if (dbTemplate != null && dbTemplate.getDqlTemplate() != null && !dbTemplate.getDqlTemplate().isBlank()) {
            return renderTemplate(dbTemplate.getDqlTemplate(), templateVariables(context));
        }
        return defaultDql(evidenceType, context);
    }

    private String payloadTemplate(SopRouteRequest alert,
                                   String evidenceType,
                                   TroubleshootingEvidenceProperties.Guance cfg,
                                   TroubleshootingQueryTemplateEntity dbTemplate) {
        String inline = labelValue(alert, payloadTemplateLabelKeys(evidenceType, false));
        if (!inline.isBlank()) return inline;
        if (dbTemplate != null && dbTemplate.getPayloadTemplate() != null && !dbTemplate.getPayloadTemplate().isBlank()) {
            return dbTemplate.getPayloadTemplate();
        }
        if (cfg.getMetricsPayloadTemplate() != null && !cfg.getMetricsPayloadTemplate().isBlank()) {
            return cfg.getMetricsPayloadTemplate();
        }
        return DQL_QUERY_PAYLOAD_TEMPLATE;
    }

    private String payloadTemplateName(SopRouteRequest alert, String evidenceType) {
        return labelValue(alert, payloadTemplateLabelKeys(evidenceType, true));
    }

    private TroubleshootingQueryTemplateEntity resolveDbTemplate(long workspaceId,
                                                                 String evidenceType,
                                                                 String requestedTemplateName,
                                                                 SopRouteRequest alert) {
        if (queryTemplateService == null) return null;
        try {
            return queryTemplateService.resolveForAlert(workspaceId, "guance", evidenceType, requestedTemplateName, alert)
                    .orElse(null);
        } catch (Exception e) {
            log.warn("[TroubleshootingEvidence] Failed to resolve Guance {} query template: {}", evidenceType, e.getMessage());
            return null;
        }
    }

    private String infrastructureSummary(SopRouteRequest alert, String evidenceType, Map<String, Object> normalized) {
        Object count = normalized.get("recordCount");
        Object names = normalized.get("objectNames");
        Object conclusion = normalized.get("infrastructureConclusion");
        return "Guance " + evidenceType + " collected " + count + " records for " + target(alert)
                + "; objectNames=" + names
                + "; conclusion=" + conclusion
                + "; sensitive fields redacted.";
    }

    private String defaultDql(String evidenceType, Map<String, Object> context) {
        return switch (evidenceType) {
            case "host" -> "D::host:(`host`, `host_name`, `ip`, `cpu_usage`, `mem_used_percent`, `status`) { `host` = '"
                    + escapeDqlString(String.valueOf(context.getOrDefault("hostName", ""))) + "' }";
            case "container" -> "D::container:(`container_name`, `pod_name`, `namespace`, `cluster`, `status`, `restart_count`) { `pod_name` = '"
                    + escapeDqlString(String.valueOf(context.getOrDefault("podName", ""))) + "' }";
            case "k8s" -> "D::container:(`container_name`, `pod_name`, `namespace`, `cluster`, `status`, `restart_count`) { `namespace` = '"
                    + escapeDqlString(String.valueOf(context.getOrDefault("namespace", ""))) + "' }";
            default -> "";
        };
    }

    private static List<String> dqlLabelKeys(String evidenceType, boolean template) {
        String prefix = evidenceTypePrefix(evidenceType);
        return template
                ? List.of(prefix + "DqlTemplate", prefix + "_dql_template", "dqlQueryTemplate", "dql_query_template")
                : List.of(prefix + "Dql", prefix + "_dql", prefix + "Query", prefix + "_query", "dqlQuery", "dql", "query");
    }

    private static List<String> payloadTemplateLabelKeys(String evidenceType, boolean name) {
        String prefix = evidenceTypePrefix(evidenceType);
        return name
                ? List.of(
                "guance" + capitalize(prefix) + "PayloadTemplateName",
                "guance_" + prefix + "_payload_template_name",
                prefix + "PayloadTemplateName",
                prefix + "_payload_template_name",
                "payloadTemplateName",
                "payload_template_name"
        )
                : List.of(
                "guance" + capitalize(prefix) + "PayloadTemplate",
                "guance_" + prefix + "_payload_template",
                prefix + "PayloadTemplate",
                prefix + "_payload_template",
                "payloadTemplate",
                "payload_template"
        );
    }

    private static String evidenceTypePrefix(String evidenceType) {
        return switch (normalizeEvidenceType(evidenceType)) {
            case "host" -> "host";
            case "container" -> "container";
            case "k8s" -> "k8s";
            default -> "infra";
        };
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

    private static boolean isInfrastructure(String evidenceType) {
        return List.of("host", "hosts", "infra_host", "infrastructure_host", "guance_host", "guance_hosts", "主机",
                        "container", "containers", "pod", "pods", "infra_container", "infrastructure_container",
                        "guance_container", "guance_containers", "guance_pod", "guance_pods", "容器",
                        "k8s", "kubernetes", "guance_k8s")
                .contains(normalizeRaw(evidenceType));
    }

    private static String normalizeEvidenceType(String evidenceType) {
        String normalized = normalizeRaw(evidenceType);
        if (List.of("hosts", "infra_host", "infrastructure_host", "guance_host", "guance_hosts", "主机").contains(normalized)) {
            return "host";
        }
        if (List.of("containers", "pod", "pods", "infra_container", "infrastructure_container",
                "guance_container", "guance_containers", "guance_pod", "guance_pods", "容器").contains(normalized)) {
            return "container";
        }
        if (List.of("kubernetes", "guance_k8s").contains(normalized)) return "k8s";
        return normalized;
    }

    private static String normalizeRaw(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
    }

    private static String escapeDqlString(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static String capitalize(String value) {
        if (value == null || value.isBlank()) return "";
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
    }
}
