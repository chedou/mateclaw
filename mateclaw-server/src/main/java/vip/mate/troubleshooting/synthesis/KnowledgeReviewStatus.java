package vip.mate.troubleshooting.synthesis;

/** v4 knowledge lifecycle. Only IN_REVIEW and REJECTED are writable in P5 today. */
public enum KnowledgeReviewStatus {
    DRAFT,
    CANDIDATE,
    IN_REVIEW,
    APPROVED,
    REJECTED,
    DEPRECATED
}
