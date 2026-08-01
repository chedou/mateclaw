package vip.mate.troubleshooting.controller;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/** Admin-supplied parameters for one non-persistent, read-only Guance check. */
public record GuanceEvidenceValidationRequest(
        @NotBlank String system,
        @NotBlank String service,
        @NotBlank String searchTerm,
        String window,
        Instant occurredAt) {
}
