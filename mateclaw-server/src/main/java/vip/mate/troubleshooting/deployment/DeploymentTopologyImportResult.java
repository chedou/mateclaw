package vip.mate.troubleshooting.deployment;

/** Import result; identical snapshots are reused instead of duplicated. */
public record DeploymentTopologyImportResult(
        DeploymentTopologyAssetSummary topology,
        boolean created) {
}
