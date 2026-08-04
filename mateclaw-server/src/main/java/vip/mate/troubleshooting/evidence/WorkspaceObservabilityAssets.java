package vip.mate.troubleshooting.evidence;

import java.util.Optional;
import java.util.Set;

/** Runtime seam for workspace-owned source scopes; deployment YAML remains the fallback. */
public interface WorkspaceObservabilityAssets {

    WorkspaceObservabilityAssets NONE = new WorkspaceObservabilityAssets() {
        @Override
        public Optional<WorkspaceObservabilityAsset> find(
                long workspaceId, String system, String service) {
            return Optional.empty();
        }

        @Override
        public Set<String> activeBindingReferences(String signalKind) {
            return Set.of();
        }
    };

    Optional<WorkspaceObservabilityAsset> find(
            long workspaceId, String system, String service);

    /** Exact binding references currently reachable from latest enabled revisions. */
    Set<String> activeBindingReferences(String signalKind);
}
