package vip.mate.troubleshooting.controller;

import jakarta.validation.constraints.NotBlank;

/** Browser input for a server-owned Guance Evidence Spine capture. */
public record GuanceEvaluationSampleCaptureRequest(
        @NotBlank String diagnosisId) {
}
