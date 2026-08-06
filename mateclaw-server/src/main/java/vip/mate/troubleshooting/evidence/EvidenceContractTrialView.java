package vip.mate.troubleshooting.evidence;

import java.time.Instant;
import java.util.List;

/** Secret-free audit projection of one read-only evidence-contract trial. */
public record EvidenceContractTrialView(
        String trialId,
        long workspaceId,
        String system,
        String service,
        String contractRef,
        String signalKind,
        String assetId,
        int assetVersion,
        Status status,
        String stopReason,
        String source,
        List<String> canonicalFields,
        long durationMs,
        String actor,
        Instant completedAt,
        String warning) {

    public EvidenceContractTrialView {
        canonicalFields = List.copyOf(canonicalFields == null ? List.of() : canonicalFields);
    }

    public enum Status {
        OBSERVED,
        NO_EVIDENCE,
        FAILED
    }
}
