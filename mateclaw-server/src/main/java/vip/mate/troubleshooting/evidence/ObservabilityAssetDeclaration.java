package vip.mate.troubleshooting.evidence;

import java.util.Map;

/** Admin declaration; endpoint hosts, credentials and raw query text are intentionally absent. */
public record ObservabilityAssetDeclaration(
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
        Integer expectedVersion,
        String reason) {
}
