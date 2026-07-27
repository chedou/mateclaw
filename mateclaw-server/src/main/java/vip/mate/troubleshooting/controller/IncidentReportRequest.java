package vip.mate.troubleshooting.controller;

import jakarta.validation.constraints.NotBlank;
import vip.mate.troubleshooting.model.EvidenceResult;
import vip.mate.troubleshooting.model.IncidentCompleteness;
import vip.mate.troubleshooting.model.IncidentContext;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Incident payload accepted from an alert webhook, a ticket system or a
 * console form.
 *
 * <p>Wire shape is deliberately separate from {@link IncidentContext} so that
 * an external caller cannot set fields the domain derives for itself, and so
 * that renaming a domain field does not silently break every alert source.</p>
 *
 * <p>{@code evidence} is optional and carries already-normalized observations.
 * Read-only source adapters land later; until then the intake service marks
 * every diagnosis as fixture-backed regardless of what the caller supplies.</p>
 */
public record IncidentReportRequest(
        String incidentId,
        @NotBlank String system,
        @NotBlank String service,
        String errorCode,
        String title,
        String severity,
        String impact,
        String traceId,
        Instant occurredAt,
        String slaRemaining,
        String intakeSource,
        IncidentCompleteness completeness,
        String rawInput,
        List<EvidenceResult> evidence,
        Boolean rehearsal) {

    public boolean isRehearsal() {
        return Boolean.TRUE.equals(rehearsal);
    }

    public List<EvidenceResult> evidenceOrEmpty() {
        return evidence == null ? List.of() : List.copyOf(evidence);
    }

    /**
     * Projects the request onto the domain contract.
     *
     * <p>A caller that omits {@code incidentId} gets a generated one, and an
     * omitted {@code completeness} defaults to {@code STRUCTURED} because the
     * request reached a route that requires an error code. Everything else is
     * validated by {@link IncidentContext} itself.</p>
     */
    public IncidentContext toIncidentContext(Instant receivedAt) {
        return new IncidentContext(
                incidentId == null || incidentId.isBlank()
                        ? "inc-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16)
                        : incidentId,
                system,
                service,
                errorCode,
                title,
                severity,
                impact,
                traceId,
                occurredAt == null ? receivedAt : occurredAt,
                slaRemaining,
                intakeSource,
                completeness == null ? IncidentCompleteness.STRUCTURED : completeness,
                rawInput);
    }
}
