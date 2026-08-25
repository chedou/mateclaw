package vip.mate.troubleshooting.evidence;

import java.util.LinkedHashMap;
import java.util.Map;
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

    /** One admin-owned asset shared by runtime services in the same system. */
    default Optional<WorkspaceObservabilityAsset> findSystem(
            long workspaceId, String system) {
        return find(
                workspaceId,
                system,
                SystemObservabilityScopePolicy.SYSTEM_SERVICE);
    }

    /** Exact binding references currently reachable from latest enabled revisions. */
    Set<String> activeBindingReferences(String signalKind);

    /**
     * Signal → binding refs from one registry pass. Catalog inspection must use
     * this instead of calling {@link #activeBindingReferences(String)} per signal.
     */
    default Map<String, Set<String>> activeBindingReferencesBySignal() {
        Map<String, Set<String>> index = new LinkedHashMap<>();
        for (String signalKind : CanonicalEvidenceSchema.externallyRoutableSignalKinds()) {
            Set<String> references = activeBindingReferences(signalKind);
            if (!references.isEmpty()) {
                index.put(signalKind, references);
            }
        }
        return Map.copyOf(index);
    }
}
