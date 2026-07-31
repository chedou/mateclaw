package vip.mate.troubleshooting.synthesis;

import java.util.Optional;

/** Persistence boundary for immutable manual-candidate replay proofs. */
public interface ManualPlaybookReplayAttestationStore {

    Optional<ManualPlaybookReplayAttestation> find(
            long workspaceId,
            String sourceRecordId,
            String candidateFingerprint,
            String suiteFingerprint);

    Stored saveOrGet(long workspaceId, ManualPlaybookReplayAttestation attestation);

    record Stored(ManualPlaybookReplayAttestation attestation, boolean created) {
        public Stored {
            if (attestation == null) {
                throw new IllegalArgumentException("attestation is required");
            }
        }
    }
}
