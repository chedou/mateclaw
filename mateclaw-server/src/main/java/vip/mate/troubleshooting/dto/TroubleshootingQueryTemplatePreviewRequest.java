package vip.mate.troubleshooting.dto;

public record TroubleshootingQueryTemplatePreviewRequest(
        TroubleshootingQueryTemplateRequest template,
        SopRouteRequest alert
) {
}
