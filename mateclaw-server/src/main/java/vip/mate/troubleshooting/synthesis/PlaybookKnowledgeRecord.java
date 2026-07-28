package vip.mate.troubleshooting.synthesis;

import vip.mate.troubleshooting.model.NorthStarTimings;

import java.time.Instant;
import java.util.List;

/** Persisted review queue item for an evidence-derived, never-active P1 draft. */
public record PlaybookKnowledgeRecord(
        String recordId,
        PlaybookDraft draft,
        String origin,
        String reviewStatus,
        String validationStatus,
        String reviewer,
        String reviewReason,
        String evidenceBundleId,
        String service,
        ReferenceSolutionComparator.Comparison referenceComparison,
        String approvalEligibility,
        List<String> eligibilityReasons,
        boolean fixtureMode,
        NorthStarTimings timings,
        Instant createdAt) {

    public PlaybookKnowledgeRecord {
        reviewer = reviewer == null ? "" : reviewer;
        reviewReason = reviewReason == null ? "" : reviewReason;
        eligibilityReasons = List.copyOf(
                eligibilityReasons == null ? List.of() : eligibilityReasons);
        if (!"EVIDENCE_DERIVED".equals(origin)
                || !"CANDIDATE".equals(reviewStatus)
                || !"VALID".equals(validationStatus)
                || !"NOT_ELIGIBLE".equals(approvalEligibility)) {
            throw new IllegalArgumentException(
                    "P1 evidence-derived records must remain valid, candidate, and not eligible");
        }
        if (recordId == null || recordId.isBlank()
                || draft == null || evidenceBundleId == null || evidenceBundleId.isBlank()
                || service == null || service.isBlank()
                || referenceComparison == null || timings == null || createdAt == null) {
            throw new IllegalArgumentException("knowledge record core fields are required");
        }
        if (!fixtureMode) {
            throw new IllegalArgumentException(
                    "P1 evidence-derived candidates must retain the fixture marker");
        }
        if (!draft.validationErrors().isEmpty()) {
            throw new IllegalArgumentException("an invalid draft cannot be persisted as a candidate");
        }
    }
}
