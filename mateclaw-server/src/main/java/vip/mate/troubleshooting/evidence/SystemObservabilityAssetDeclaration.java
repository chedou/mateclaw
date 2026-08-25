package vip.mate.troubleshooting.evidence;

import java.util.Map;

/** Admin input for a system-level asset; no module/service is user-maintained. */
public record SystemObservabilityAssetDeclaration(
        String system,
        String displayName,
        String platform,
        String environment,
        String region,
        String cluster,
        String namespace,
        boolean enabled,
        Map<String, String> signalBindings,
        Map<String, String> parameters,
        Integer expectedVersion,
        String reason) {
}
