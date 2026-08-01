package vip.mate.troubleshooting.controller;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Browser input for a server-owned Guance Evidence Spine capture. */
public record GuanceEvaluationSampleCaptureRequest(
        @NotBlank String diagnosisId,
        @NotBlank
        @Pattern(regexp = "[a-z][a-z0-9_:-]{1,63}")
        String scenarioKey,
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")
        String searchTerm,
        String window) {
}
