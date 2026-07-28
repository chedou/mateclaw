package vip.mate.troubleshooting.synthesis;

import java.time.Instant;

/** Full P1 generation input, including the two Intake-owned north-star timestamps. */
public record PlaybookSynthesisRequest(
        SopSynthesisRequest evidenceRequest,
        String sourceIncidentId,
        Instant reportedAt,
        Instant readyAt) {

    public PlaybookSynthesisRequest {
        if (evidenceRequest == null) {
            throw new IllegalArgumentException("evidenceRequest is required");
        }
        if (sourceIncidentId == null || sourceIncidentId.isBlank()) {
            throw new IllegalArgumentException("sourceIncidentId is required");
        }
        sourceIncidentId = sourceIncidentId.trim();
        if (reportedAt == null || readyAt == null || readyAt.isBefore(reportedAt)) {
            throw new IllegalArgumentException(
                    "reportedAt and chronological readyAt are required");
        }
    }
}
