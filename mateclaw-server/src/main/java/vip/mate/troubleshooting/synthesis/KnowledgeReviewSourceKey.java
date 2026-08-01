package vip.mate.troubleshooting.synthesis;

/** Stable identity used to join an Inbox source with its independent review state. */
public record KnowledgeReviewSourceKey(
        KnowledgeOrigin origin,
        String sourceRecordId) {

    public KnowledgeReviewSourceKey {
        if (origin == null || sourceRecordId == null || sourceRecordId.isBlank()) {
            throw new IllegalArgumentException("knowledge review source key is required");
        }
        sourceRecordId = sourceRecordId.trim();
    }
}
