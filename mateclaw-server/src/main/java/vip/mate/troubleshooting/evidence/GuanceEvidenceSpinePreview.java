package vip.mate.troubleshooting.evidence;

import java.time.Instant;
import java.util.List;

/**
 * Secret-free projection of one Guance-only Evidence Spine run.
 *
 * <p>The projection exposes structural counts, the bounded service sequence and
 * deterministic contrast measurements. It deliberately omits source query text,
 * raw trace rows and log messages.</p>
 */
public record GuanceEvidenceSpinePreview(
        Stage stage,
        GuanceEvidenceReadiness readiness,
        Long matchCount,
        String psId,
        Integer traceEntries,
        List<String> serviceSequence,
        int anomalyCount,
        Long traceElapsedMs,
        Contrast contrast,
        int sourceRequestCount,
        long totalDurationMs,
        List<Step> steps,
        Instant completedAt,
        List<String> warnings) {

    static final String SEARCH_EVIDENCE_REF = "T8-GUANCE-LOG-SEARCH";
    static final String TRACE_EVIDENCE_REF = "T8-GUANCE-TRACE-BUNDLE";
    static final String CONTRAST_EVIDENCE_REF = "T8-GUANCE-CONTRAST-SAMPLE";
    private static final List<String> SIGNAL_KINDS = List.of(
            "log_search", "log_trace_bundle", "contrast_sample");
    private static final List<String> EVIDENCE_REFS = List.of(
            SEARCH_EVIDENCE_REF, TRACE_EVIDENCE_REF, CONTRAST_EVIDENCE_REF);

    public GuanceEvidenceSpinePreview {
        stage = stage == null ? Stage.BLOCKED : stage;
        if (readiness == null) {
            throw new IllegalArgumentException("readiness is required");
        }
        psId = psId == null ? null : psId.trim();
        serviceSequence = List.copyOf(serviceSequence == null ? List.of() : serviceSequence);
        if (anomalyCount < 0) {
            throw new IllegalArgumentException("anomalyCount must not be negative");
        }
        if (traceElapsedMs != null && traceElapsedMs < 0) {
            throw new IllegalArgumentException("traceElapsedMs must not be negative");
        }
        contrast = contrast == null ? Contrast.unavailable() : contrast;
        if (sourceRequestCount < 0 || sourceRequestCount > 3) {
            throw new IllegalArgumentException("sourceRequestCount must be between 0 and 3");
        }
        totalDurationMs = Math.max(0L, totalDurationMs);
        steps = List.copyOf(steps == null ? List.of() : steps);
        validateSteps(stage, steps);
        completedAt = completedAt == null ? Instant.EPOCH : completedAt;
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
        boolean coreObserved = stage != Stage.BLOCKED;
        if (coreObserved
                && (matchCount == null || matchCount <= 0L
                        || psId == null || psId.isBlank()
                        || traceEntries == null || traceEntries <= 0
                        || serviceSequence.isEmpty()
                        || traceElapsedMs == null
                        || sourceRequestCount != 3)) {
            throw new IllegalArgumentException(
                    "an observed core chain requires complete structural facts");
        }
        if ((stage == Stage.FULL_SPINE_OBSERVED) != contrast.available()) {
            throw new IllegalArgumentException(
                    "full-spine stage and contrast availability must agree");
        }
    }

    private static void validateSteps(Stage stage, List<Step> steps) {
        if (steps.size() != SIGNAL_KINDS.size()) {
            throw new IllegalArgumentException("the Evidence Spine requires exactly three steps");
        }
        for (int index = 0; index < SIGNAL_KINDS.size(); index++) {
            Step step = steps.get(index);
            if (!SIGNAL_KINDS.get(index).equals(step.signalKind())
                    || !EVIDENCE_REFS.get(index).equals(step.evidenceRef())) {
                throw new IllegalArgumentException(
                        "Evidence Spine step order and references are fixed");
            }
            if (step.status() == StepStatus.CANONICAL_RESULT_OBSERVED
                    && step.collectedAt() == null) {
                throw new IllegalArgumentException(
                        "an observed Evidence Spine step requires collectedAt");
            }
            if (step.status() == StepStatus.NOT_RUN && step.collectedAt() != null) {
                throw new IllegalArgumentException(
                        "a non-executed Evidence Spine step cannot have collectedAt");
            }
        }

        StepStatus search = steps.get(0).status();
        StepStatus trace = steps.get(1).status();
        StepStatus contrast = steps.get(2).status();
        if (search != StepStatus.CANONICAL_RESULT_OBSERVED
                && (trace != StepStatus.NOT_RUN || contrast != StepStatus.NOT_RUN)) {
            throw new IllegalArgumentException("trace and contrast cannot run before search");
        }
        if (trace != StepStatus.CANONICAL_RESULT_OBSERVED
                && contrast != StepStatus.NOT_RUN) {
            throw new IllegalArgumentException("contrast cannot run before the core trace");
        }
        boolean coreObserved = search == StepStatus.CANONICAL_RESULT_OBSERVED
                && trace == StepStatus.CANONICAL_RESULT_OBSERVED;
        if ((stage != Stage.BLOCKED) != coreObserved) {
            throw new IllegalArgumentException("stage and core step status must agree");
        }
        if (stage == Stage.FULL_SPINE_OBSERVED
                && contrast != StepStatus.CANONICAL_RESULT_OBSERVED) {
            throw new IllegalArgumentException("a full spine requires observed contrast evidence");
        }
        if (stage == Stage.CORE_CHAIN_OBSERVED && contrast != StepStatus.MISSING) {
            throw new IllegalArgumentException("a core-only spine requires explicit missing contrast");
        }
    }

    public enum Stage {
        BLOCKED,
        CORE_CHAIN_OBSERVED,
        FULL_SPINE_OBSERVED
    }

    public enum StepStatus {
        NOT_RUN,
        MISSING,
        CANONICAL_RESULT_OBSERVED
    }

    public record Step(
            String signalKind,
            StepStatus status,
            String evidenceRef,
            Instant collectedAt) {

        public Step {
            signalKind = signalKind == null ? "" : signalKind.trim();
            status = status == null ? StepStatus.MISSING : status;
            evidenceRef = evidenceRef == null ? "" : evidenceRef.trim();
        }
    }

    public record Contrast(
            boolean available,
            long failureSampleCount,
            long failureMatchCount,
            long successSampleCount,
            long successMatchCount,
            double failureRate,
            double successRate,
            double rateDelta) {

        public Contrast {
            if (!available) {
                if (failureSampleCount != 0L || failureMatchCount != 0L
                        || successSampleCount != 0L || successMatchCount != 0L
                        || failureRate != 0D || successRate != 0D || rateDelta != 0D) {
                    throw new IllegalArgumentException(
                            "unavailable contrast must not contain invented measurements");
                }
            } else if (failureSampleCount <= 0L
                    || successSampleCount <= 0L
                    || failureMatchCount < 0L
                    || successMatchCount < 0L
                    || failureMatchCount > failureSampleCount
                    || successMatchCount > successSampleCount
                    || !unit(failureRate)
                    || !unit(successRate)
                    || !Double.isFinite(rateDelta)
                    || rateDelta < -1D
                    || rateDelta > 1D) {
                throw new IllegalArgumentException("contrast measurements are invalid");
            }
            if (available) {
                double expectedFailureRate = roundedRate(
                        failureMatchCount, failureSampleCount);
                double expectedSuccessRate = roundedRate(
                        successMatchCount, successSampleCount);
                double expectedDelta = rounded(
                        expectedFailureRate - expectedSuccessRate);
                if (Double.compare(failureRate, expectedFailureRate) != 0
                        || Double.compare(successRate, expectedSuccessRate) != 0
                        || Double.compare(rateDelta, expectedDelta) != 0) {
                    throw new IllegalArgumentException(
                            "contrast rates must be reproducible from the sample counts");
                }
            }
        }

        public static Contrast unavailable() {
            return new Contrast(false, 0L, 0L, 0L, 0L, 0D, 0D, 0D);
        }

        private static boolean unit(double value) {
            return Double.isFinite(value) && value >= 0D && value <= 1D;
        }

        private static double roundedRate(long matches, long samples) {
            return rounded((double) matches / samples);
        }

        private static double rounded(double value) {
            return Math.round(value * 1_000_000D) / 1_000_000D;
        }
    }
}
