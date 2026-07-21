package vip.mate.troubleshooting.dto;

import java.util.Map;

public record TroubleshootingQueryTemplatePreviewResponse(
        String provider,
        String evidenceType,
        String templateKey,
        String status,
        String source,
        String title,
        String summary,
        String endpoint,
        Object request,
        Map<String, Object> normalized,
        String responsePreview,
        String error,
        Long durationMs
) {
}
