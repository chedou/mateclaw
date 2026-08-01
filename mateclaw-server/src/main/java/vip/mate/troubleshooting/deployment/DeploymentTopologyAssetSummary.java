package vip.mate.troubleshooting.deployment;

import java.time.Instant;

/** Secret-free selector projection for one shared topology snapshot. */
public record DeploymentTopologyAssetSummary(
        String topologyId,
        String name,
        String system,
        String systemLabel,
        String schemaVersion,
        Instant exportedAt,
        int nodeCount,
        int linkCount,
        int configuredProbeNodes,
        String importedBy,
        Instant importedAt) {
}
