package vip.mate.troubleshooting.dto;

import java.util.LinkedHashMap;
import java.util.Map;

public record SopRouteRequest(
        String eventId,
        String source,
        String severity,
        String alertName,
        String status,
        String serviceName,
        String env,
        String cluster,
        String namespace,
        String pod,
        String instance,
        String endpoint,
        String metricName,
        String message,
        String rawText,
        Map<String, Object> labels,
        Integer topK
) {
    public Map<String, Object> safeLabels() {
        return labels == null ? Map.of() : new LinkedHashMap<>(labels);
    }
}
