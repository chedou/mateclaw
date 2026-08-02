package vip.mate.troubleshooting.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import vip.mate.troubleshooting.evaluation.EvidenceEvaluationSample;

/** Human-authored structural oracle. Diagnosis outcome remains server-owned. */
public record EvaluationSampleReferenceRequest(
        @NotNull @Min(0) Integer expectedVersion,
        @NotEmpty @Size(max = 20) List<String> requiredStepIntents,
        @NotEmpty @Size(max = 20) List<String> forbiddenStepIntents,
        @NotNull EvidenceEvaluationSample.ExpectedDisposition expectedDisposition,
        /**
         * How long this incident actually took a human. Optional, because a
         * sample whose historical duration nobody can source is still worth
         * scoring for correctness — but a cohort without it can only answer
         * "准不准", never "省不省时间".
         */
        @Valid EvidenceEvaluationSample.HumanBaseline humanBaseline) {

    public EvaluationSampleReferenceRequest {
        requiredStepIntents = List.copyOf(
                requiredStepIntents == null ? List.of() : requiredStepIntents);
        forbiddenStepIntents = List.copyOf(
                forbiddenStepIntents == null ? List.of() : forbiddenStepIntents);
    }
}
