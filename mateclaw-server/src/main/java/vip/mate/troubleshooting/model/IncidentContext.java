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
        IncidentImpact impact,
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
        impact = impact == null ? IncidentImpact.unknown("待确认") : impact;
        traceId = normalizeNullable(traceId);
        slaRemaining = normalizeNullable(slaRemaining);
        intakeSource = blankDefault(intakeSource, "manual");
        completeness = completeness == null ? IncidentCompleteness.STRUCTURED : completeness;
    }

    /** Java-source compatibility for callers that still provide the pre-v1.6 string impact. */
    public IncidentContext(
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
        this(
                incidentId,
                system,
                service,
                errorCode,
                title,
                severity,
                IncidentImpact.unknown(impact),
                traceId,
                occurredAt,
                slaRemaining,
                intakeSource,
                completeness,
                rawInput);
    }

    /**
     * Stamps a server-resolved route onto an alert that named no error code.
     *
     * <p>An alert routed by symptom is decided by an approved Playbook whose
     * selector is {@code scenario:<key>}. Carrying that selector on the
     * incident keeps one invariant intact: a deterministic diagnosis always
     * names the exact route that produced it, so the record can be re-read and
     * re-derived later. Without it, the scenario lane would have to be a second
     * diagnosis path with weaker identity.</p>
     *
     * <p>Only the server calls this, and only when the report named no code.
     * Overwriting a reported code would let routing rewrite the evidence of
     * what was actually reported.</p>
     */
    public IncidentContext withResolvedRoute(String resolvedErrorCode) {
        String resolved = required(resolvedErrorCode, "resolvedErrorCode");
        if (errorCode != null) {
            throw new IllegalStateException(
                    "incident already names error code " + errorCode);
        }
        return new IncidentContext(
                incidentId, system, service, resolved, title, severity, impact,
                traceId, occurredAt, slaRemaining, intakeSource, completeness, rawInput);
    }

    /** Replaces a process-entrypoint placeholder with one corroborated service identity. */
    public IncidentContext withResolvedService(String resolvedService) {
        return new IncidentContext(
                incidentId, system, required(resolvedService, "resolvedService"), errorCode,
                title, severity, impact, traceId, occurredAt, slaRemaining,
                intakeSource, completeness, rawInput);
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
