package vip.mate.troubleshooting.event;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Lightweight in-process event; durable knowledge publication uses the outbox. */
public record TroubleshootingDomainEvent(
        String type,
        String diagnosisId,
        Instant occurredAt,
        Map<String, Object> attributes) {

    public TroubleshootingDomainEvent {
        if (type == null || type.isBlank() || diagnosisId == null || diagnosisId.isBlank()) {
            throw new IllegalArgumentException("type and diagnosisId are required");
        }
        occurredAt = occurredAt == null ? Instant.EPOCH : occurredAt;
        attributes = Collections.unmodifiableMap(
                new LinkedHashMap<>(attributes == null ? Map.of() : attributes));
    }
}
