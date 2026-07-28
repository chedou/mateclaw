package vip.mate.troubleshooting.controller;

import jakarta.validation.constraints.NotBlank;
import vip.mate.troubleshooting.synthesis.SopSynthesisRequest;

import java.time.Instant;

/** Wire contract for the read-only log-to-call-chain preview. */
public record SopSynthesisPreviewRequest(
        @NotBlank String system,
        @NotBlank String service,
        @NotBlank String searchTerm,
        String window,
        Instant occurredAt) {

    public SopSynthesisRequest toDomainRequest() {
        return new SopSynthesisRequest(system, service, searchTerm, window, occurredAt);
    }
}
