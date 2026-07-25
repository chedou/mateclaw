package vip.mate.troubleshooting.model;

import java.time.Instant;

/** Immutable incident intake contract shared by deterministic routing and UI. */
public record IncidentContext(
        String incidentId,
        String system,
        String service,
        String errorCode,
        String title,
        String severity,
        String impact,
        String traceId,
        Instant occurredAt,
        String slaRemaining,
        String intakeSource,
        IncidentCompleteness completeness,
        String rawInput) {

    public IncidentContext {
        incidentId = required(incidentId, "incidentId");
        system = required(system, "system");
        service = required(service, "service");
        errorCode = normalizeNullable(errorCode);
        title = title == null ? "" : title;
        severity = blankDefault(severity, "P2");
        impact = blankDefault(impact, "待确认");
        traceId = normalizeNullable(traceId);
        slaRemaining = normalizeNullable(slaRemaining);
        intakeSource = blankDefault(intakeSource, "manual");
        completeness = completeness == null ? IncidentCompleteness.STRUCTURED : completeness;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static String normalizeNullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
