package vip.mate.troubleshooting.model;

import java.time.Instant;

/** Result reported by a human after an action was executed outside MateClaw. */
public record ActionOutcomeRecord(
        String outcomeId,
        String actionId,
        ActionOutcomeStatus outcome,
        String notes,
        boolean recoveryVerified,
        String actor,
        Instant recordedAt) {

    public ActionOutcomeRecord {
        outcomeId = required(outcomeId, "outcomeId");
        actionId = required(actionId, "actionId");
        if (outcome == null) {
            throw new IllegalArgumentException("outcome must not be null");
        }
        notes = required(notes, "notes");
        actor = required(actor, "actor");
        recordedAt = recordedAt == null ? Instant.EPOCH : recordedAt;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
