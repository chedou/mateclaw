package vip.mate.troubleshooting.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import vip.mate.troubleshooting.synthesis.PlaybookSynthesisRequest;
import vip.mate.troubleshooting.synthesis.SopSynthesisRequest;

import java.time.Instant;

/** Wire contract for the review-only P1 PlaybookDraft generation endpoint. */
public record PlaybookSynthesisGenerateRequest(
        @NotBlank String system,
        @NotBlank String service,
        @NotBlank String searchTerm,
        String window,
        Instant occurredAt,
        @NotBlank String sourceIncidentId,
        @NotNull Instant reportedAt,
        @NotNull Instant readyAt) {

    public PlaybookSynthesisRequest toDomainRequest() {
        return new PlaybookSynthesisRequest(
                new SopSynthesisRequest(system, service, searchTerm, window, occurredAt),
                sourceIncidentId, reportedAt, readyAt);
    }
}
