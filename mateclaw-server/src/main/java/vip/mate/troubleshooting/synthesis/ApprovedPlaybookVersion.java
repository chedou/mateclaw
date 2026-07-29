package vip.mate.troubleshooting.synthesis;

import vip.mate.troubleshooting.model.SopEntry;

import java.time.Instant;

/** Safe, immutable projection of one persisted approved Playbook version. */
public record ApprovedPlaybookVersion(
        String playbookId,
        int playbookVersion,
        String selectorKey,
        String status,
        String sourceOrigin,
        String sourceRecordId,
        String reviewId,
        Integer reviewVersion,
        String approvedBy,
        String approvalReason,
        KnowledgeReviewSnapshot approvalSnapshot,
        String deprecatedBy,
        String deprecationReason,
        Instant deprecatedAt,
        SopEntry playbook,
        Instant createdAt,
        Instant updatedAt) {

    public ApprovedPlaybookVersion {
        if (playbookId == null || playbookId.isBlank()
                || playbookVersion < 1
                || selectorKey == null || selectorKey.isBlank()
                || status == null || status.isBlank()
                || sourceOrigin == null || sourceOrigin.isBlank()
                || sourceRecordId == null || sourceRecordId.isBlank()
                || approvedBy == null || approvedBy.isBlank()
                || approvalReason == null || approvalReason.isBlank()
                || playbook == null
                || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("approved Playbook version fields are required");
        }
        playbookId = playbookId.trim();
        selectorKey = selectorKey.trim();
        status = status.trim();
        sourceOrigin = sourceOrigin.trim();
        sourceRecordId = sourceRecordId.trim();
        reviewId = normalize(reviewId);
        approvedBy = approvedBy.trim();
        approvalReason = approvalReason.trim();
        deprecatedBy = normalize(deprecatedBy);
        deprecationReason = normalize(deprecationReason);
        if ((deprecatedBy == null) != (deprecationReason == null)
                || (deprecatedBy == null) != (deprecatedAt == null)) {
            throw new IllegalArgumentException(
                    "Playbook deprecation audit fields must be present together");
        }
    }

    /** Compatibility constructor for projections without deprecation audit fields. */
    public ApprovedPlaybookVersion(
            String playbookId,
            int playbookVersion,
            String selectorKey,
            String status,
            String sourceOrigin,
            String sourceRecordId,
            String reviewId,
            Integer reviewVersion,
            String approvedBy,
            String approvalReason,
            KnowledgeReviewSnapshot approvalSnapshot,
            SopEntry playbook,
            Instant createdAt,
            Instant updatedAt) {
        this(
                playbookId, playbookVersion, selectorKey, status,
                sourceOrigin, sourceRecordId, reviewId, reviewVersion,
                approvedBy, approvalReason, approvalSnapshot,
                null, null, null, playbook, createdAt, updatedAt);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
