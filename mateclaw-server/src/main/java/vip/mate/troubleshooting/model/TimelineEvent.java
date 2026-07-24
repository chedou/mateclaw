package vip.mate.troubleshooting.model;

import java.time.Instant;

public record TimelineEvent(Instant timestamp, String event, String actor, String status) {
    public TimelineEvent {
        timestamp = timestamp == null ? Instant.EPOCH : timestamp;
        event = required(event, "event");
        actor = required(actor, "actor");
        status = status == null || status.isBlank() ? "done" : status.trim();
    }

    public TimelineEvent done() {
        return "done".equals(status) ? this : new TimelineEvent(timestamp, event, actor, "done");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
