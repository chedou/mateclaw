package vip.mate.troubleshooting.evidence;

import java.util.Optional;

/** Workspace-scoped persistence port for immutable T7 owner acceptances. */
public interface GuanceEvidenceAcceptanceStore {

    Optional<GuanceEvidenceAcceptance> findByFingerprint(
            long workspaceId,
            String scopeKey,
            String bindingFingerprint);

    Optional<GuanceEvidenceAcceptance> findLatest(
            long workspaceId,
            String scopeKey);

    StoredAcceptance saveOrGet(
            long workspaceId,
            String scopeKey,
            GuanceEvidenceAcceptance acceptance);

    record StoredAcceptance(
            GuanceEvidenceAcceptance acceptance,
            boolean created) {

        public StoredAcceptance {
            if (acceptance == null) {
                throw new IllegalArgumentException("acceptance is required");
            }
        }
    }
}
