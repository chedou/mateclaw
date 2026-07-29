package vip.mate.troubleshooting.evaluation;

import vip.mate.troubleshooting.TroubleshootingBusinessTextPolicy;
import vip.mate.troubleshooting.evidence.EvidenceSpineTimings;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpinePreview;
import vip.mate.troubleshooting.model.ClosureOutcome;
import vip.mate.troubleshooting.synthesis.ReferenceSolution;

import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Secret-free historical sample used to calibrate the fixed troubleshooting evaluation set.
 *
 * <p>The aggregate deliberately stores only the structural Evidence Spine projection. Source
 * search terms, DQL, credentials, raw rows and log messages are not part of this contract.</p>
 */
public record EvidenceEvaluationSample(
        String sampleId,
        String sampleKey,
        String diagnosisId,
        String system,
        String service,
        String scenarioKey,
        SourcePlatform sourcePlatform,
        EvidenceSnapshot evidence,
        boolean diagnosisFixtureMode,
        ReferenceStatus referenceStatus,
        ReferenceSolution referenceSolution,
        OutcomeSnapshot outcome,
        int version,
        String capturedBy,
        String finalizedBy,
        Instant capturedAt,
        Instant finalizedAt) {

    private static final Pattern STRUCTURED_KEY =
            Pattern.compile("[a-z][a-z0-9_:-]{1,63}");
    private static final List<String> SIGNAL_KINDS = List.of(
            "log_search", "log_trace_bundle", "contrast_sample");
    private static final List<String> EVIDENCE_REFS = List.of(
            "T8-GUANCE-LOG-SEARCH",
            "T8-GUANCE-TRACE-BUNDLE",
            "T8-GUANCE-CONTRAST-SAMPLE");

    public EvidenceEvaluationSample {
        sampleId = required(sampleId, "sampleId");
        sampleKey = required(sampleKey, "sampleKey");
        if (!sampleKey.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("sampleKey must be a SHA-256 value");
        }
        diagnosisId = required(diagnosisId, "diagnosisId");
        system = required(system, "system");
        service = required(service, "service");
        scenarioKey = required(scenarioKey, "scenarioKey");
        if (!STRUCTURED_KEY.matcher(scenarioKey).matches()) {
            throw new IllegalArgumentException("scenarioKey must be a structured intent key");
        }
        if (sourcePlatform == null || evidence == null || referenceStatus == null) {
            throw new IllegalArgumentException(
                    "sourcePlatform, evidence and referenceStatus are required");
        }
        if (sourcePlatform == SourcePlatform.GUANCE && evidence.fixtureMode()) {
            throw new IllegalArgumentException("Guance evidence cannot be fixture evidence");
        }
        if (sourcePlatform == SourcePlatform.RECORDED_REPLAY && !evidence.fixtureMode()) {
            throw new IllegalArgumentException(
                    "Recorded Replay evidence must remain fixture evidence");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        capturedBy = required(capturedBy, "capturedBy");
        capturedAt = requiredInstant(capturedAt, "capturedAt");

        if (referenceStatus == ReferenceStatus.EVIDENCE_CAPTURED) {
            if (referenceSolution != null || outcome != null
                    || finalizedBy != null || finalizedAt != null || version != 0) {
                throw new IllegalArgumentException(
                        "an evidence-only sample cannot contain finalized reference facts");
            }
        } else {
            if (referenceSolution == null || outcome == null
                    || finalizedBy == null || finalizedBy.isBlank()
                    || finalizedAt == null || version < 1) {
                throw new IllegalArgumentException(
                        "a ready sample requires reference, outcome and finalization facts");
            }
            finalizedBy = finalizedBy.trim();
        }
    }

    /** Creates a Guance sample from an observed, secret-free Evidence Spine projection. */
    public static EvidenceEvaluationSample captured(
            String sampleId,
            String sampleKey,
            String diagnosisId,
            String system,
            String service,
            String scenarioKey,
            GuanceEvidenceSpinePreview preview,
            boolean diagnosisFixtureMode,
            String actor,
            Instant capturedAt) {
        if (preview == null || preview.stage() == GuanceEvidenceSpinePreview.Stage.BLOCKED) {
            throw new IllegalArgumentException(
                    "an evaluation sample requires observed Guance evidence");
        }
        return new EvidenceEvaluationSample(
                sampleId,
                sampleKey,
                diagnosisId,
                system,
                service,
                scenarioKey,
                SourcePlatform.GUANCE,
                EvidenceSnapshot.from(preview),
                diagnosisFixtureMode,
                ReferenceStatus.EVIDENCE_CAPTURED,
                null,
                null,
                0,
                actor,
                null,
                capturedAt,
                null);
    }

    /** Finalizes the immutable human-authored oracle and authoritative Diagnosis outcome. */
    public EvidenceEvaluationSample finalizeReference(
            ReferenceSolution reference,
            OutcomeSnapshot authoritativeOutcome,
            String actor,
            Instant finalizedAt) {
        if (referenceStatus != ReferenceStatus.EVIDENCE_CAPTURED) {
            throw new IllegalStateException("evaluation sample reference is already finalized");
        }
        if (reference == null || authoritativeOutcome == null) {
            throw new IllegalArgumentException("reference and authoritative outcome are required");
        }
        if (!scenarioKey.equals(reference.scenarioKey())) {
            throw new IllegalArgumentException("reference scenario must match the evaluation sample");
        }
        Instant normalizedFinalizedAt = requiredInstant(finalizedAt, "finalizedAt");
        return new EvidenceEvaluationSample(
                sampleId,
                sampleKey,
                diagnosisId,
                system,
                service,
                scenarioKey,
                sourcePlatform,
                evidence,
                diagnosisFixtureMode,
                ReferenceStatus.READY_FOR_EVALUATION,
                reference,
                authoritativeOutcome,
                version + 1,
                capturedBy,
                required(actor, "actor"),
                capturedAt,
                normalizedFinalizedAt);
    }

    public enum SourcePlatform {
        GUANCE,
        RECORDED_REPLAY
    }

    public enum ReferenceStatus {
        EVIDENCE_CAPTURED,
        READY_FOR_EVALUATION
    }

    /** Structural Evidence Spine facts safe for persistence and later aggregate scoring. */
    public record EvidenceSnapshot(
            GuanceEvidenceSpinePreview.Stage stage,
            boolean fixtureMode,
            Long matchCount,
            String psId,
            Integer traceEntries,
            List<String> serviceSequence,
            int anomalyCount,
            Long traceElapsedMs,
            ContrastSnapshot contrast,
            int sourceRequestCount,
            long totalDurationMs,
            EvidenceSpineTimings timings,
            List<StepSnapshot> steps,
            Instant completedAt) {

        public EvidenceSnapshot(
                GuanceEvidenceSpinePreview.Stage stage,
                boolean fixtureMode,
                Long matchCount,
                String psId,
                Integer traceEntries,
                List<String> serviceSequence,
                int anomalyCount,
                Long traceElapsedMs,
                ContrastSnapshot contrast,
                int sourceRequestCount,
                long totalDurationMs,
                List<StepSnapshot> steps,
                Instant completedAt) {
            this(
                    stage,
                    fixtureMode,
                    matchCount,
                    psId,
                    traceEntries,
                    serviceSequence,
                    anomalyCount,
                    traceElapsedMs,
                    contrast,
                    sourceRequestCount,
                    totalDurationMs,
                    EvidenceSpineTimings.unmeasured(),
                    steps,
                    completedAt);
        }

        public EvidenceSnapshot {
            if (stage == null || stage == GuanceEvidenceSpinePreview.Stage.BLOCKED) {
                throw new IllegalArgumentException("evidence snapshot must contain an observed spine");
            }
            if (matchCount == null || matchCount <= 0
                    || psId == null || psId.isBlank()
                    || traceEntries == null || traceEntries <= 0
                    || traceElapsedMs == null || traceElapsedMs < 0) {
                throw new IllegalArgumentException("evidence snapshot core facts are incomplete");
            }
            psId = psId.trim();
            serviceSequence = List.copyOf(serviceSequence == null ? List.of() : serviceSequence);
            if (serviceSequence.isEmpty() || anomalyCount < 0) {
                throw new IllegalArgumentException("evidence snapshot sequence facts are invalid");
            }
            contrast = contrast == null ? ContrastSnapshot.unavailable() : contrast;
            if ((stage == GuanceEvidenceSpinePreview.Stage.FULL_SPINE_OBSERVED)
                    != contrast.available()) {
                throw new IllegalArgumentException("evidence stage and contrast must agree");
            }
            if (sourceRequestCount != 3) {
                throw new IllegalArgumentException("an observed Evidence Spine requires three requests");
            }
            if (totalDurationMs < 0) {
                throw new IllegalArgumentException("totalDurationMs must not be negative");
            }
            timings = timings == null ? EvidenceSpineTimings.unmeasured() : timings;
            Long measuredWorkDurationMs = timings.measuredWorkDurationMs();
            if (measuredWorkDurationMs != null && totalDurationMs < measuredWorkDurationMs) {
                throw new IllegalArgumentException(
                        "totalDurationMs cannot be shorter than the measured Evidence Spine work");
            }
            steps = List.copyOf(steps == null ? List.of() : steps);
            if (steps.size() != 3) {
                throw new IllegalArgumentException("evidence snapshot requires three steps");
            }
            validateSteps(stage, steps);
            completedAt = requiredInstant(completedAt, "completedAt");
        }

        static EvidenceSnapshot from(GuanceEvidenceSpinePreview preview) {
            return new EvidenceSnapshot(
                    preview.stage(),
                    false,
                    preview.matchCount(),
                    preview.psId(),
                    preview.traceEntries(),
                    preview.serviceSequence(),
                    preview.anomalyCount(),
                    preview.traceElapsedMs(),
                    ContrastSnapshot.from(preview.contrast()),
                    preview.sourceRequestCount(),
                    preview.totalDurationMs(),
                    preview.timings(),
                    preview.steps().stream().map(StepSnapshot::from).toList(),
                    preview.completedAt());
        }
    }

    public record ContrastSnapshot(
            boolean available,
            long failureSampleCount,
            long failureMatchCount,
            long successSampleCount,
            long successMatchCount,
            double failureRate,
            double successRate,
            double rateDelta) {

        public ContrastSnapshot {
            // Reuse the canonical preview invariant so persisted snapshots cannot
            // manufacture rates that are inconsistent with their source counts.
            new GuanceEvidenceSpinePreview.Contrast(
                    available,
                    failureSampleCount,
                    failureMatchCount,
                    successSampleCount,
                    successMatchCount,
                    failureRate,
                    successRate,
                    rateDelta);
        }

        static ContrastSnapshot from(GuanceEvidenceSpinePreview.Contrast contrast) {
            return new ContrastSnapshot(
                    contrast.available(),
                    contrast.failureSampleCount(),
                    contrast.failureMatchCount(),
                    contrast.successSampleCount(),
                    contrast.successMatchCount(),
                    contrast.failureRate(),
                    contrast.successRate(),
                    contrast.rateDelta());
        }

        static ContrastSnapshot unavailable() {
            return new ContrastSnapshot(false, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    public record StepSnapshot(
            String signalKind,
            GuanceEvidenceSpinePreview.StepStatus status,
            String evidenceRef,
            Instant collectedAt) {

        public StepSnapshot {
            signalKind = required(signalKind, "signalKind");
            evidenceRef = required(evidenceRef, "evidenceRef");
            if (status == null) {
                throw new IllegalArgumentException("step status is required");
            }
            if (status == GuanceEvidenceSpinePreview.StepStatus.CANONICAL_RESULT_OBSERVED
                    && collectedAt == null) {
                throw new IllegalArgumentException("an observed step requires collectedAt");
            }
            if (status == GuanceEvidenceSpinePreview.StepStatus.NOT_RUN
                    && collectedAt != null) {
                throw new IllegalArgumentException("a non-executed step cannot have collectedAt");
            }
        }

        static StepSnapshot from(GuanceEvidenceSpinePreview.Step step) {
            return new StepSnapshot(
                    step.signalKind(), step.status(), step.evidenceRef(), step.collectedAt());
        }
    }

    public record OutcomeSnapshot(
            ClosureOutcome outcome,
            String summary,
            boolean recoveryVerified,
            Instant closedAt) {

        public OutcomeSnapshot {
            if (outcome == null) {
                throw new IllegalArgumentException("outcome is required");
            }
            if ((outcome == ClosureOutcome.RECOVERED) != recoveryVerified) {
                throw new IllegalArgumentException(
                        "recovery verification must agree with a recovered outcome");
            }
            summary = TroubleshootingBusinessTextPolicy.requireSafeClosureSummary(summary);
            closedAt = requiredInstant(closedAt, "closedAt");
        }
    }

    private static void validateSteps(
            GuanceEvidenceSpinePreview.Stage stage,
            List<StepSnapshot> steps) {
        for (int index = 0; index < SIGNAL_KINDS.size(); index++) {
            StepSnapshot step = steps.get(index);
            if (!SIGNAL_KINDS.get(index).equals(step.signalKind())
                    || !EVIDENCE_REFS.get(index).equals(step.evidenceRef())) {
                throw new IllegalArgumentException(
                        "evidence snapshot step order and references are fixed");
            }
        }
        GuanceEvidenceSpinePreview.StepStatus search = steps.get(0).status();
        GuanceEvidenceSpinePreview.StepStatus trace = steps.get(1).status();
        GuanceEvidenceSpinePreview.StepStatus contrast = steps.get(2).status();
        if (search != GuanceEvidenceSpinePreview.StepStatus.CANONICAL_RESULT_OBSERVED
                || trace != GuanceEvidenceSpinePreview.StepStatus.CANONICAL_RESULT_OBSERVED) {
            throw new IllegalArgumentException(
                    "an observed evidence snapshot requires the core chain");
        }
        if (stage == GuanceEvidenceSpinePreview.Stage.FULL_SPINE_OBSERVED
                && contrast
                != GuanceEvidenceSpinePreview.StepStatus.CANONICAL_RESULT_OBSERVED) {
            throw new IllegalArgumentException("a full evidence snapshot requires contrast");
        }
        if (stage == GuanceEvidenceSpinePreview.Stage.CORE_CHAIN_OBSERVED
                && contrast != GuanceEvidenceSpinePreview.StepStatus.MISSING) {
            throw new IllegalArgumentException(
                    "a core-only evidence snapshot requires explicit missing contrast");
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static Instant requiredInstant(Instant value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }
}
