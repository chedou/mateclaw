package vip.mate.troubleshooting.model;

import java.time.Instant;

public record ClosureRecord(
        ClosureOutcome outcome,
        String summary,
        boolean recoveryVerified,
        String sopFeedback,
        String knowledgeCandidateId,
        String actor,
        Instant closedAt) {

    public ClosureRecord {
        if (outcome == null) {
            throw new IllegalArgumentException("outcome must not be null");
        }
        summary = required(summary, "summary");
        actor = required(actor, "actor");
        closedAt = closedAt == null ? Instant.EPOCH : closedAt;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
