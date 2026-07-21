package vip.mate.troubleshooting.dto;

public record TroubleshootingQueryTemplateRequest(
        String provider,
        String evidenceType,
        String templateKey,
        String name,
        String description,
        String payloadTemplate,
        String dqlTemplate,
        String matchJson,
        Boolean enabled,
        Boolean defaultTemplate,
        Integer priority
) {
}
