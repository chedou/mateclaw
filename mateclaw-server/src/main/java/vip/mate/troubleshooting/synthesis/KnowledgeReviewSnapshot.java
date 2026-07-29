package vip.mate.troubleshooting.synthesis;

import java.util.List;

/**
 * Safe source facts frozen when a reviewer starts work.
 *
 * <p>This intentionally excludes raw evidence, rendered queries, search terms,
 * PS IDs and credentials. Those remain behind canonical evidence references.</p>
 */
public record KnowledgeReviewSnapshot(
        String validationStatus,
        List<PlaybookDraft.ValidationError> validationErrors,
        ReferenceSolutionComparator.Comparison referenceComparison,
        String modelConfigVersion,
        String approvalEligibility,
        List<String> eligibilityReasons,
        Boolean fixtureMode) {

    public KnowledgeReviewSnapshot {
        if (validationStatus == null || validationStatus.isBlank()) {
            throw new IllegalArgumentException("validationStatus is required");
        }
        if (approvalEligibility == null || approvalEligibility.isBlank()) {
            throw new IllegalArgumentException("approvalEligibility is required");
        }
        validationErrors = List.copyOf(
                validationErrors == null ? List.of() : validationErrors);
        eligibilityReasons = List.copyOf(
                eligibilityReasons == null ? List.of() : eligibilityReasons);
        modelConfigVersion = normalizeOptional(modelConfigVersion);
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
