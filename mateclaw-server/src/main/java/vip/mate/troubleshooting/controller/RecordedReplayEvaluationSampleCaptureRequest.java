package vip.mate.troubleshooting.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Null;

/** Browser lookup input for a server-owned, fixture-confined recorded Replay capture. */
public record RecordedReplayEvaluationSampleCaptureRequest(
        @NotBlank String diagnosisId,
        @Null(message = "Replay scenarioKey is server-owned")
        String scenarioKey,
        @Null(message = "Replay searchTerm is server-owned")
        String searchTerm,
        @Null(message = "Replay window is server-owned")
        String window) {
}
