package vip.mate.troubleshooting.evidence;

import java.util.Map;
import java.util.Optional;

/** Runtime seam for workspace-managed evidence contracts; deployment YAML remains the base. */
public interface WorkspaceEvidenceContracts {

    WorkspaceEvidenceContracts NONE = new WorkspaceEvidenceContracts() {
        @Override
        public Map<String, EvidenceProperties.Binding> bindings(long workspaceId) {
            return Map.of();
        }

        @Override
        public Optional<EvidenceProperties.Binding> find(long workspaceId, String contractRef) {
            return Optional.empty();
        }
    };

    /** Latest enabled workspace contracts keyed by contractRef. */
    Map<String, EvidenceProperties.Binding> bindings(long workspaceId);

    Optional<EvidenceProperties.Binding> find(long workspaceId, String contractRef);
}
