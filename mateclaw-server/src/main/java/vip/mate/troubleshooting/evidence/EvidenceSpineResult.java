package vip.mate.troubleshooting.evidence;

import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.synthesis.LogTraceSkeleton;

import java.util.ArrayList;
import java.util.List;

/** Canonical evidence and deterministic model-safe projection from one spine run. */
public record EvidenceSpineResult(
        EvidenceResult searchEvidence,
        EvidenceResult traceEvidence,
        EvidenceResult contrastEvidence,
        LogTraceSkeleton skeleton,
        int sourceRequestCount,
        EvidenceSpineTimings timings,
        String coreFailure) {

    public EvidenceSpineResult(
            EvidenceResult searchEvidence,
            EvidenceResult traceEvidence,
            EvidenceResult contrastEvidence,
            LogTraceSkeleton skeleton,
            int sourceRequestCount,
            String coreFailure) {
        this(
                searchEvidence,
                traceEvidence,
                contrastEvidence,
                skeleton,
                sourceRequestCount,
                EvidenceSpineTimings.unmeasured(),
                coreFailure);
    }

    public EvidenceSpineResult {
        if (searchEvidence == null) {
            throw new IllegalArgumentException("searchEvidence is required");
        }
        if (sourceRequestCount < 1 || sourceRequestCount > 3) {
            throw new IllegalArgumentException("sourceRequestCount must be between 1 and 3");
        }
        timings = timings == null ? EvidenceSpineTimings.unmeasured() : timings;
        coreFailure = coreFailure == null || coreFailure.isBlank()
                ? null
                : coreFailure.trim();
        if (skeleton != null && traceEvidence == null) {
            throw new IllegalArgumentException("a skeleton requires trace evidence");
        }
    }

    public boolean coreComplete() {
        return skeleton != null && coreFailure == null;
    }

    public boolean contrastAvailable() {
        return coreComplete() && skeleton.contrast().available();
    }

    public List<EvidenceResult> evidence() {
        List<EvidenceResult> result = new ArrayList<>(3);
        result.add(searchEvidence);
        if (traceEvidence != null) {
            result.add(traceEvidence);
        }
        if (contrastEvidence != null) {
            result.add(contrastEvidence);
        }
        return List.copyOf(result);
    }
}
