package vip.mate.troubleshooting.dto;

import vip.mate.troubleshooting.model.TroubleshootingConnectorConfigEntity;

public record TroubleshootingConnectorConfigResponse(
        String provider,
        boolean persisted,
        boolean enabled,
        String baseUrl,
        String syntheticsPath,
        String metricsPath,
        String tokenHeader,
        String tokenPrefix,
        boolean tokenConfigured,
        String tokenSource,
        String window,
        Integer syntheticsLimit,
        String metricsWindow,
        Integer metricsLimit,
        Integer maxResponseChars
) {

    public static TroubleshootingConnectorConfigResponse fromEntity(TroubleshootingConnectorConfigEntity entity,
                                                                    boolean tokenConfigured,
                                                                    String tokenSource) {
        return new TroubleshootingConnectorConfigResponse(
                entity.getProvider(),
                true,
                Integer.valueOf(1).equals(entity.getEnabled()),
                entity.getBaseUrl(),
                entity.getSyntheticsPath(),
                entity.getMetricsPath(),
                entity.getTokenHeader(),
                entity.getTokenPrefix(),
                tokenConfigured,
                tokenSource,
                entity.getTimeWindow(),
                entity.getSyntheticsLimit(),
                entity.getMetricsWindow(),
                entity.getMetricsLimit(),
                entity.getMaxResponseChars()
        );
    }
}
