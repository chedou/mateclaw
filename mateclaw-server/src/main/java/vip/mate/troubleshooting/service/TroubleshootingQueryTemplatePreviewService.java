package vip.mate.troubleshooting.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.dto.SopRouteRequest;
import vip.mate.troubleshooting.dto.TroubleshootingQueryTemplatePreviewRequest;
import vip.mate.troubleshooting.dto.TroubleshootingQueryTemplatePreviewResponse;
import vip.mate.troubleshooting.dto.TroubleshootingQueryTemplateRequest;
import vip.mate.troubleshooting.evidence.CollectedEvidence;
import vip.mate.troubleshooting.evidence.EvidenceCollectionRequest;
import vip.mate.troubleshooting.evidence.EvidenceConnector;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class TroubleshootingQueryTemplatePreviewService {

    private final List<EvidenceConnector> connectors;

    public TroubleshootingQueryTemplatePreviewResponse preview(long workspaceId,
                                                              TroubleshootingQueryTemplatePreviewRequest request) {
        TroubleshootingQueryTemplateRequest template = request == null ? null : request.template();
        if (template == null) {
            throw new MateClawException("Query template preview requires a template");
        }
        String provider = normalize(value(template.provider(), "guance"));
        String evidenceType = normalize(value(template.evidenceType(), "synthetics"));
        if (!"guance".equals(provider)) {
            throw new MateClawException("Only guance query template preview is supported now");
        }

        SopRouteRequest alert = withTemplateLabels(request.alert(), template);
        EvidenceCollectionRequest collectRequest = new EvidenceCollectionRequest(
                workspaceId,
                "query-template-preview",
                null,
                null,
                alert,
                evidenceType
        );
        EvidenceConnector connector = connectors.stream()
                .filter(item -> item.supports(evidenceType))
                .filter(item -> item.id().startsWith("guance-"))
                .min(Comparator.comparingInt(EvidenceConnector::order))
                .orElseThrow(() -> new MateClawException("No Guance connector supports evidenceType: " + evidenceType));
        List<CollectedEvidence> evidence = connector.collect(collectRequest);
        CollectedEvidence item = evidence.isEmpty() ? null : evidence.get(0);
        Map<String, Object> content = item == null || item.content() == null ? Map.of() : item.content();
        return new TroubleshootingQueryTemplatePreviewResponse(
                provider,
                evidenceType,
                value(template.templateKey(), ""),
                item == null ? "unavailable" : item.status(),
                item == null ? "guance-synthetics" : item.source(),
                item == null ? "Guance synthetics unavailable" : item.title(),
                item == null ? "No preview evidence returned" : item.summary(),
                stringValue(content.get("endpoint")),
                content.get("request"),
                mapValue(content.get("normalized")),
                stringValue(firstPresent(content, "responsePreview", "rawPreview")),
                stringValue(content.get("error")),
                longValue(content.get("durationMs"))
        );
    }

    private SopRouteRequest withTemplateLabels(SopRouteRequest alert, TroubleshootingQueryTemplateRequest template) {
        Map<String, Object> labels = alert == null ? new LinkedHashMap<>() : alert.safeLabels();
        putIfNotBlank(labels, "syntheticsPayloadTemplateName", template.templateKey());
        putIfNotBlank(labels, "syntheticsPayloadTemplate", template.payloadTemplate());
        putIfNotBlank(labels, "payloadTemplateName", template.templateKey());
        putIfNotBlank(labels, "payloadTemplate", template.payloadTemplate());
        putIfNotBlank(labels, "dqlQueryTemplate", template.dqlTemplate());
        return new SopRouteRequest(
                alert == null ? null : alert.eventId(),
                alert == null ? "query-template-preview" : alert.source(),
                alert == null ? null : alert.severity(),
                alert == null ? null : alert.alertName(),
                alert == null ? null : alert.status(),
                alert == null ? null : alert.serviceName(),
                alert == null ? null : alert.env(),
                alert == null ? null : alert.cluster(),
                alert == null ? null : alert.namespace(),
                alert == null ? null : alert.pod(),
                alert == null ? null : alert.instance(),
                alert == null ? null : alert.endpoint(),
                alert == null ? null : alert.metricName(),
                alert == null ? null : alert.message(),
                alert == null ? null : alert.rawText(),
                labels,
                alert == null ? null : alert.topK()
        );
    }

    private static void putIfNotBlank(Map<String, Object> labels, String key, String value) {
        if (value != null && !value.isBlank()) {
            labels.put(key, value.trim());
        }
    }

    private static Object firstPresent(Map<String, Object> content, String... keys) {
        for (String key : keys) {
            Object value = content.get(key);
            if (value != null) return value;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static String value(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
