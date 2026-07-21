package vip.mate.troubleshooting.dto;

public record TroubleshootingConnectorConfigRequest(
        Boolean enabled,
        String baseUrl,
        String syntheticsPath,
        String metricsPath,
        String token,
        Boolean clearToken,
        String tokenHeader,
        String tokenPrefix,
        String window,
        Integer syntheticsLimit,
        String metricsWindow,
        Integer metricsLimit,
        Integer maxResponseChars
) {
}
