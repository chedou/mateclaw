package vip.mate.troubleshooting.evaluation;

import java.util.List;
import java.util.Optional;

/** Workspace-scoped persistence port for the T8 historical sample ledger. */
public interface EvidenceEvaluationSampleStore {

    Optional<EvidenceEvaluationSample> findBySampleKey(long workspaceId, String sampleKey);

    Optional<EvidenceEvaluationSample> findLatestByCaptureIdentity(
            long workspaceId,
            String captureIdentityKey);

    Optional<EvidenceEvaluationSample> get(long workspaceId, String sampleId);

    StoredSample saveOrGet(long workspaceId, EvidenceEvaluationSample sample);

    EvidenceEvaluationSample finalizeReference(
            long workspaceId,
            EvidenceEvaluationSample sample,
            int expectedVersion);

    List<EvidenceEvaluationSample> list(long workspaceId, String diagnosisId, int limit);

    record StoredSample(EvidenceEvaluationSample sample, boolean created) {
        public StoredSample {
            if (sample == null) {
                throw new IllegalArgumentException("sample is required");
            }
        }
    }
}
