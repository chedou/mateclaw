package vip.mate.troubleshooting.synthesis;

/** One atomic deprecation decision and the authority it retired. */
public record KnowledgeReviewDeprecation(
        KnowledgeReviewState review,
        ApprovedPlaybookVersion deprecatedVersion) {

    public KnowledgeReviewDeprecation {
        if (review == null || deprecatedVersion == null) {
            throw new IllegalArgumentException("deprecation result fields are required");
        }
    }
}
