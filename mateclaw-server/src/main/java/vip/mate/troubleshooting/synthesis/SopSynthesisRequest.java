package vip.mate.troubleshooting.synthesis;

import java.time.Instant;

/** Operator-supplied, read-only input for the log-learning preview. */
public record SopSynthesisRequest(
        String system,
        String service,
        String searchTerm,
        String window,
        Instant occurredAt) {

    public SopSynthesisRequest {
        system = required(system, "system");
        service = required(service, "service");
        searchTerm = required(searchTerm, "searchTerm");
        window = window == null || window.isBlank() ? "-15m" : window.trim();
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
