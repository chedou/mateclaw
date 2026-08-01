package vip.mate.troubleshooting.controller;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Lookup-only input; evidence and the model-visible skeleton are rebuilt by the server. */
public record BaselineEvaluationRunRequest(
        @NotNull @Min(1) Integer expectedSampleVersion,
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")
        String searchTerm,
        String window) {
}
