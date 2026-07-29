package vip.mate.troubleshooting.synthesis;

/** Workspace-validated source identity and the safe facts frozen for review. */
public record KnowledgeReviewSource(
        KnowledgeOrigin origin,
        String sourceRecordId,
        String selectorKey,
        KnowledgeReviewSnapshot snapshot) {

    public KnowledgeReviewSource {
        if (origin == null
                || sourceRecordId == null || sourceRecordId.isBlank()
                || snapshot == null) {
            throw new IllegalArgumentException("knowledge review source fields are required");
        }
        sourceRecordId = sourceRecordId.trim();
        selectorKey = selectorKey == null || selectorKey.isBlank()
                ? null : selectorKey.trim();
    }
}
