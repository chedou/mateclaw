package vip.mate.troubleshooting.evidence;

import java.util.Map;

/** Runtime-only, secret-free projection of one exact workspace resource. */
public record WorkspaceObservabilityAsset(
        String assetId,
        long workspaceId,
        String system,
        String service,
        String platform,
        boolean enabled,
        Map<String, String> signalBindings,
        Map<String, String> parameters,
        int version) {

    public WorkspaceObservabilityAsset {
        signalBindings = Map.copyOf(signalBindings == null ? Map.of() : signalBindings);
        parameters = Map.copyOf(parameters == null ? Map.of() : parameters);
    }
}
