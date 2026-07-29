package vip.mate.troubleshooting.controller;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Human-authored structural oracle. Diagnosis outcome remains server-owned. */
public record EvaluationSampleReferenceRequest(
        @NotNull @Min(0) Integer expectedVersion,
        @NotEmpty @Size(max = 20) List<String> requiredStepIntents,
        @NotEmpty @Size(max = 20) List<String> forbiddenStepIntents) {

    public EvaluationSampleReferenceRequest {
        requiredStepIntents = List.copyOf(
                requiredStepIntents == null ? List.of() : requiredStepIntents);
        forbiddenStepIntents = List.copyOf(
                forbiddenStepIntents == null ? List.of() : forbiddenStepIntents);
    }
}
