package vip.mate.troubleshooting.synthesis;

import java.time.Instant;

/** Persisted review decision state, independent from candidate publication. */
public record KnowledgeReviewState(
        String reviewId,
        KnowledgeOrigin origin,
        String sourceRecordId,
        String selectorKey,
        KnowledgeReviewStatus status,
        String reviewer,
        String reason,
        KnowledgeReviewSnapshot snapshot,
        int version,
        Instant createdAt,
        Instant updatedAt) {

    public KnowledgeReviewState {
        if (reviewId == null || reviewId.isBlank()
                || origin == null
                || sourceRecordId == null || sourceRecordId.isBlank()
                || status == null
                || reviewer == null || reviewer.isBlank()
                || reason == null || reason.isBlank()
                || snapshot == null
                || version < 1
                || createdAt == null || updatedAt == null) {
            throw new IllegalArgumentException("knowledge review state fields are required");
        }
        reviewId = reviewId.trim();
        sourceRecordId = sourceRecordId.trim();
        selectorKey = selectorKey == null || selectorKey.isBlank()
                ? null : selectorKey.trim();
        reviewer = reviewer.trim();
        reason = reason.trim();
    }
}
