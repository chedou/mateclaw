package vip.mate.troubleshooting.evaluation;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import vip.mate.troubleshooting.evidence.GuanceEvidenceSpinePreview;

/**
 * One model-version-specific single-Agent shadow evaluation result.
 *
 * <p>No draft text, abstain reason, raw evidence, source lookup material, candidate,
 * approval state, or gate verdict is persisted in this contract.</p>
 */
public record BaselineEvaluationRun(
        String runId,
        String runKey,
        String sampleId,
        String diagnosisId,
        int sampleVersion,
        EvidenceEvaluationSample.SourcePlatform sourcePlatform,
        boolean evidenceFixtureMode,
        boolean diagnosisFixtureMode,
        GuanceEvidenceSpinePreview.Stage evidenceStage,
        String modelInputHash,
        Status status,
        List<String> modelErrorCodes,
        ValidationSnapshot validation,
        QualitySnapshot quality,
        ModelSnapshot model,
        long evidenceDurationMs,
        long modelDurationMs,
        long composedTotalDurationMs,
        String executedBy,
        Instant executedAt) {

    private static final Pattern HASH = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,95}");

    /**
     * Backward-compatible constructor for persisted runs written before the
     * Evidence Spine stage became part of the confidence authority contract.
     * Unknown history is deliberately treated as core-only, never as HIGH.
     */
    public BaselineEvaluationRun(
            String runId,
            String runKey,
            String sampleId,
            String diagnosisId,
            int sampleVersion,
            EvidenceEvaluationSample.SourcePlatform sourcePlatform,
            boolean evidenceFixtureMode,
            boolean diagnosisFixtureMode,
            String modelInputHash,
            Status status,
            List<String> modelErrorCodes,
            ValidationSnapshot validation,
            QualitySnapshot quality,
            ModelSnapshot model,
            long evidenceDurationMs,
            long modelDurationMs,
            long composedTotalDurationMs,
            String executedBy,
            Instant executedAt) {
        this(
                runId,
                runKey,
                sampleId,
                diagnosisId,
                sampleVersion,
                sourcePlatform,
                evidenceFixtureMode,
                diagnosisFixtureMode,
                GuanceEvidenceSpinePreview.Stage.CORE_CHAIN_OBSERVED,
                modelInputHash,
                status,
                modelErrorCodes,
                validation,
                quality,
                model,
                evidenceDurationMs,
                modelDurationMs,
                composedTotalDurationMs,
                executedBy,
                executedAt);
    }

    public BaselineEvaluationRun {
        runId = required(runId, "runId");
        runKey = hash(runKey, "runKey");
        sampleId = required(sampleId, "sampleId");
        diagnosisId = required(diagnosisId, "diagnosisId");
        if (sampleVersion < 1) {
            throw new IllegalArgumentException("sampleVersion must reference a finalized sample");
        }
        if (sourcePlatform == null || status == null || validation == null
                || quality == null || model == null) {
            throw new IllegalArgumentException(
                    "source, status, validation, quality and model are required");
        }
        if (sourcePlatform == EvidenceEvaluationSample.SourcePlatform.GUANCE
                && evidenceFixtureMode) {
            throw new IllegalArgumentException("Guance baseline evidence cannot be fixture data");
        }
        if (sourcePlatform == EvidenceEvaluationSample.SourcePlatform.RECORDED_REPLAY
                && !evidenceFixtureMode) {
            throw new IllegalArgumentException(
                    "Recorded Replay baseline evidence must remain fixture data");
        }
        // Missing on historical JSON means "not proven full", never infer HIGH.
        evidenceStage = evidenceStage == null
                ? GuanceEvidenceSpinePreview.Stage.CORE_CHAIN_OBSERVED
                : evidenceStage;
        if (evidenceStage == GuanceEvidenceSpinePreview.Stage.BLOCKED) {
            throw new IllegalArgumentException(
                    "a baseline run must reference an observed Evidence Spine");
        }
        modelInputHash = hash(modelInputHash, "modelInputHash");
        modelErrorCodes = List.copyOf(
                modelErrorCodes == null ? List.of() : modelErrorCodes);
        if (modelErrorCodes.stream().anyMatch(code -> code == null
                || !ERROR_CODE.matcher(code).matches())) {
            throw new IllegalArgumentException(
                    "model errors must be bounded structured codes");
        }
        if (evidenceDurationMs < 0 || modelDurationMs < 0 || composedTotalDurationMs < 0) {
            throw new IllegalArgumentException("evaluation durations must not be negative");
        }
        long expectedTotal = Math.addExact(evidenceDurationMs, modelDurationMs);
        if (composedTotalDurationMs != expectedTotal) {
            throw new IllegalArgumentException(
                    "composed duration must equal evidence plus model duration");
        }
        executedBy = required(executedBy, "executedBy");
        if (executedAt == null) {
            throw new IllegalArgumentException("executedAt is required");
        }
        validateStatus(status, modelErrorCodes, validation, quality);
    }

    /**
     * Authority assigned by the server from facts fixed before the human
     * reference solution scores correctness. It is not model self-report.
     */
    public SystemConfidence systemConfidence() {
        if (status != Status.SCORED) {
            return SystemConfidence.NOT_ASSESSED;
        }
        boolean hasHighAuthority = sourcePlatform == EvidenceEvaluationSample.SourcePlatform.GUANCE
                && !evidenceFixtureMode
                && !diagnosisFixtureMode
                && evidenceStage == GuanceEvidenceSpinePreview.Stage.FULL_SPINE_OBSERVED
                && Boolean.TRUE.equals(quality.citationComplete());
        return hasHighAuthority ? SystemConfidence.HIGH : SystemConfidence.MEDIUM;
    }

    /** Human-oracle error over an independently server-assigned HIGH run. */
    public boolean highConfidenceError() {
        return systemConfidence() == SystemConfidence.HIGH
                && quality.classification() != Classification.HELPFUL;
    }

    private static void validateStatus(
            Status status,
            List<String> modelErrorCodes,
            ValidationSnapshot validation,
            QualitySnapshot quality) {
        switch (status) {
            case MODEL_REJECTED -> {
                if (modelErrorCodes.isEmpty() || validation.executed()
                        || quality.actualDisposition() != ActualDisposition.NONE
                        || quality.classification() != Classification.TECHNICAL_FAILURE) {
                    throw new IllegalArgumentException(
                            "a rejected model call must remain an unscored technical failure");
                }
            }
            case ABSTAINED -> {
                if (!modelErrorCodes.isEmpty() || !validation.executed() || !validation.valid()
                        || quality.actualDisposition() != ActualDisposition.ABSTAIN) {
                    throw new IllegalArgumentException(
                            "an abstained call requires a valid abstention protocol check");
                }
            }
            case VALIDATION_REJECTED -> {
                if (!modelErrorCodes.isEmpty() || !validation.executed() || validation.valid()
                        || quality.actualDisposition() == ActualDisposition.NONE) {
                    throw new IllegalArgumentException(
                            "a validation rejection requires an invalid model disposition");
                }
            }
            case SCORED -> {
                if (!modelErrorCodes.isEmpty() || !validation.executed() || !validation.valid()
                        || quality.actualDisposition() != ActualDisposition.DRAFT
                        || quality.citationComplete() == null
                        || quality.requiredIntentCoverage() == null) {
                    throw new IllegalArgumentException(
                            "a scored run requires a valid structurally compared draft");
                }
            }
        }
    }

    public enum Status {
        MODEL_REJECTED,
        ABSTAINED,
        VALIDATION_REJECTED,
        SCORED
    }

    public enum ActualDisposition {
        NONE,
        DRAFT,
        ABSTAIN
    }

    public enum SystemConfidence {
        NOT_ASSESSED,
        MEDIUM,
        HIGH
    }

    /** Per-sample structural category, not a T8 acceptance or promotion verdict. */
    public enum Classification {
        HELPFUL,
        UNHELPFUL,
        HARMFUL_BLOCKED,
        TECHNICAL_FAILURE
    }

    public record ValidationSnapshot(
            boolean executed,
            boolean valid,
            List<String> errorCodes) {

        public ValidationSnapshot {
            errorCodes = List.copyOf(errorCodes == null ? List.of() : errorCodes);
            if (!executed && (valid || !errorCodes.isEmpty())) {
                throw new IllegalArgumentException(
                        "a validation that did not run cannot contain a result");
            }
            if (executed && valid != errorCodes.isEmpty()) {
                throw new IllegalArgumentException(
                        "validation validity and error codes are inconsistent");
            }
            if (errorCodes.stream().anyMatch(code -> code == null
                    || !ERROR_CODE.matcher(code).matches())) {
                throw new IllegalArgumentException(
                        "validation errors must be bounded structured codes");
            }
        }

        public static ValidationSnapshot notRun() {
            return new ValidationSnapshot(false, false, List.of());
        }
    }

    public record QualitySnapshot(
            EvidenceEvaluationSample.ExpectedDisposition expectedDisposition,
            ActualDisposition actualDisposition,
            Classification classification,
            Boolean citationComplete,
            Double requiredIntentCoverage,
            List<String> missingStepIntents,
            List<String> forbiddenStepIntentsPresent,
            List<String> orderingViolations,
            List<String> missingEvidenceKinds,
            List<String> abstainAssessmentCodes,
            boolean dangerousProposalDetected) {

        public QualitySnapshot {
            if (expectedDisposition == null || actualDisposition == null
                    || classification == null) {
                throw new IllegalArgumentException(
                        "expected, actual and classification are required");
            }
            missingStepIntents = immutable(missingStepIntents);
            forbiddenStepIntentsPresent = immutable(forbiddenStepIntentsPresent);
            orderingViolations = immutable(orderingViolations);
            missingEvidenceKinds = immutable(missingEvidenceKinds);
            abstainAssessmentCodes = immutable(abstainAssessmentCodes);
            if (abstainAssessmentCodes.stream().anyMatch(code -> code == null
                    || !ERROR_CODE.matcher(code).matches())) {
                throw new IllegalArgumentException(
                        "abstention assessments must be bounded structured codes");
            }
            if (requiredIntentCoverage != null
                    && (!Double.isFinite(requiredIntentCoverage)
                    || requiredIntentCoverage < 0
                    || requiredIntentCoverage > 1)) {
                throw new IllegalArgumentException(
                        "required intent coverage must be between zero and one");
            }
            boolean hasComparison = requiredIntentCoverage != null;
            if (!hasComparison && (citationComplete != null
                    || !missingStepIntents.isEmpty()
                    || !forbiddenStepIntentsPresent.isEmpty()
                    || !orderingViolations.isEmpty()
                    || !missingEvidenceKinds.isEmpty())) {
                throw new IllegalArgumentException(
                        "an unscored quality snapshot cannot contain comparison facts");
            }
            if (actualDisposition != ActualDisposition.ABSTAIN
                    && !abstainAssessmentCodes.isEmpty()) {
                throw new IllegalArgumentException(
                        "only an abstention can contain abstention assessment codes");
            }
            if (actualDisposition == ActualDisposition.ABSTAIN
                    && classification == Classification.HELPFUL
                    && !abstainAssessmentCodes.isEmpty()) {
                throw new IllegalArgumentException(
                        "a helpful abstention must have a safe evidence-grounded reason");
            }
            if (!forbiddenStepIntentsPresent.isEmpty() && !dangerousProposalDetected) {
                throw new IllegalArgumentException(
                        "forbidden intents must be marked as a dangerous proposal");
            }
            if (dangerousProposalDetected && classification == Classification.HELPFUL) {
                throw new IllegalArgumentException(
                        "a dangerous proposal cannot be classified as helpful");
            }
        }
    }

    public record ModelSnapshot(
            String provider,
            String modelName,
            String modelConfigVersion,
            Instant calledAt,
            int invocationCount,
            Long promptTokens,
            Long completionTokens,
            Long totalTokens) {

        public ModelSnapshot {
            provider = required(provider, "provider");
            modelName = required(modelName, "modelName");
            modelConfigVersion = required(modelConfigVersion, "modelConfigVersion");
            if (calledAt == null || invocationCount != 1) {
                throw new IllegalArgumentException(
                        "a baseline requires one timestamped model invocation");
            }
            boolean noUsage = promptTokens == null
                    && completionTokens == null
                    && totalTokens == null;
            boolean fullUsage = promptTokens != null
                    && completionTokens != null
                    && totalTokens != null;
            if (!noUsage && !fullUsage) {
                throw new IllegalArgumentException(
                        "token usage must be either complete or unavailable");
            }
            if (fullUsage && (promptTokens < 0 || completionTokens < 0
                    || totalTokens < promptTokens
                    || totalTokens < completionTokens)) {
                throw new IllegalArgumentException("token usage is invalid");
            }
        }
    }

    private static List<String> immutable(List<String> values) {
        return List.copyOf(values == null ? List.of() : values);
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String hash(String value, String field) {
        String normalized = required(value, field);
        if (!HASH.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " must be a SHA-256 value");
        }
        return normalized;
    }
}
