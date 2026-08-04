package vip.mate.troubleshooting.evidence;

import java.time.Instant;
import java.util.Map;

/** Secret-free effective asset shown in the maintenance catalog. */
public record ObservabilityAssetView(
        String assetId,
        String origin,
        long workspaceId,
        String system,
        String service,
        String displayName,
        String platform,
        String environment,
        String region,
        String cluster,
        String namespace,
        boolean enabled,
        Map<String, String> signalBindings,
        Map<String, String> parameters,
        int version,
        String changedBy,
        String reason,
        Instant changedAt) {

    public ObservabilityAssetView {
        signalBindings = Map.copyOf(signalBindings == null ? Map.of() : signalBindings);
        parameters = Map.copyOf(parameters == null ? Map.of() : parameters);
    }
}
