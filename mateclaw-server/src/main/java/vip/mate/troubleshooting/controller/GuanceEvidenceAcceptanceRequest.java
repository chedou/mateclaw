package vip.mate.troubleshooting.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import vip.mate.troubleshooting.evidence.GuanceEvidenceAcceptance;

import java.time.Instant;

/**
 * Owner-supplied T7 assertions. Evidence facts, binding fingerprints and the
 * audit actor are always derived again by the server.
 */
public record GuanceEvidenceAcceptanceRequest(
        @NotBlank String system,
        @NotBlank String service,
        @NotBlank String searchTerm,
        String window,
        Instant occurredAt,
        @Valid @NotNull GuanceEvidenceAcceptance.Checklist checklist) {
}
