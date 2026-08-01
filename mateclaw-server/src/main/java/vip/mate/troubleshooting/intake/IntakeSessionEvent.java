package vip.mate.troubleshooting.intake;

import java.time.Instant;

/** Auditable intake-only transition; no diagnosis state is represented here. */
public record IntakeSessionEvent(
        Instant at,
        IntakeSessionStatus status,
        String sourceMessageId) {

    public IntakeSessionEvent {
        if (at == null || status == null || sourceMessageId == null || sourceMessageId.isBlank()) {
            throw new IllegalArgumentException("intake timeline event is incomplete");
        }
        sourceMessageId = sourceMessageId.trim();
    }
}
