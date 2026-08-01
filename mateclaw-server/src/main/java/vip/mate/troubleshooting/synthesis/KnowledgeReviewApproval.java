package vip.mate.troubleshooting.synthesis;

/** One atomic review decision and the immutable Playbook version it created. */
public record KnowledgeReviewApproval(
        KnowledgeReviewState review,
        ApprovedPlaybookVersion approvedVersion) {

    public KnowledgeReviewApproval {
        if (review == null || approvedVersion == null) {
            throw new IllegalArgumentException("approval result fields are required");
        }
    }
}
