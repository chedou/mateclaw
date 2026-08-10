package vip.mate.troubleshooting.evaluation;

import vip.mate.troubleshooting.TroubleshootingBusinessTextPolicy;
import vip.mate.troubleshooting.evidence.EvidenceSpineTimings;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpinePreview;
import vip.mate.troubleshooting.model.ClosureOutcome;
import vip.mate.troubleshooting.synthesis.LogTraceSkeleton;
import vip.mate.troubleshooting.synthesis.ReferenceSolution;
import vip.mate.troubleshooting.synthesis.SopSynthesisPreview;

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
        String captureIdentityKey,
        int captureRevision,
        String diagnosisId,
        String system,
        String service,
        String scenarioKey,
        SourcePlatform sourcePlatform,
        EvidenceSnapshot evidence,
        String modelInputHash,
        Instant evidenceOccurredAt,
        boolean diagnosisFixtureMode,
        ReferenceStatus referenceStatus,
        ReferenceSolution referenceSolution,
        ExpectedDisposition expectedDisposition,
        HumanBaseline humanBaseline,
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
    private static final List<String> GUANCE_EVIDENCE_REFS = List.of(
            "T8-GUANCE-LOG-SEARCH",
            "T8-GUANCE-TRACE-BUNDLE",
            "T8-GUANCE-CONTRAST-SAMPLE");
    private static final List<String> REPLAY_EVIDENCE_REFS = List.of(
            "SYNTH-LOG-SEARCH",
            "SYNTH-TRACE-BUNDLE",
            "SYNTH-CONTRAST-SAMPLE");

    public EvidenceEvaluationSample {
        sampleId = required(sampleId, "sampleId");
        sampleKey = required(sampleKey, "sampleKey");
        if (!sampleKey.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("sampleKey must be a SHA-256 value");
        }
        captureIdentityKey = captureIdentityKey == null || captureIdentityKey.isBlank()
                ? sampleKey
                : captureIdentityKey.trim();
        if (!captureIdentityKey.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException(
                    "captureIdentityKey must be a SHA-256 value");
        }
        if (captureRevision == 0) {
            // V181/V182 JSON predates explicit immutable recapture revisions.
            captureRevision = 1;
        } else if (captureRevision < 0) {
            throw new IllegalArgumentException("captureRevision must be positive");
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
        modelInputHash = optionalHash(modelInputHash, "modelInputHash");
        if (version < 0) {
            throw new IllegalArgumentException("version must not be negative");
        }
        capturedBy = required(capturedBy, "capturedBy");
        capturedAt = requiredInstant(capturedAt, "capturedAt");

        if (referenceStatus == ReferenceStatus.EVIDENCE_CAPTURED) {
            if (referenceSolution != null || outcome != null
                    || expectedDisposition != null
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

    /** Backward-compatible canonical constructor for JSON/callers predating recapture revisions. */
    public EvidenceEvaluationSample(
            String sampleId,
            String sampleKey,
            String diagnosisId,
            String system,
            String service,
            String scenarioKey,
            SourcePlatform sourcePlatform,
            EvidenceSnapshot evidence,
            String modelInputHash,
            Instant evidenceOccurredAt,
            boolean diagnosisFixtureMode,
            ReferenceStatus referenceStatus,
            ReferenceSolution referenceSolution,
            ExpectedDisposition expectedDisposition,
            OutcomeSnapshot outcome,
            int version,
            String capturedBy,
            String finalizedBy,
            Instant capturedAt,
            Instant finalizedAt) {
        this(
                sampleId,
                sampleKey,
                sampleKey,
                1,
                diagnosisId,
                system,
                service,
                scenarioKey,
                sourcePlatform,
                evidence,
                modelInputHash,
                evidenceOccurredAt,
                diagnosisFixtureMode,
                referenceStatus,
                referenceSolution,
                expectedDisposition,
                null,
                outcome,
                version,
                capturedBy,
                finalizedBy,
                capturedAt,
                finalizedAt);
    }

    /** Backward-compatible constructor for V181 JSON and callers predating model input scoring. */
    public EvidenceEvaluationSample(
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
        this(
                sampleId,
                sampleKey,
                sampleKey,
                1,
                diagnosisId,
                system,
                service,
                scenarioKey,
                sourcePlatform,
                evidence,
                null,
                null,
                diagnosisFixtureMode,
                referenceStatus,
                referenceSolution,
                null,
                null,
                outcome,
                version,
                capturedBy,
                finalizedBy,
                capturedAt,
                finalizedAt);
    }

    /** Creates a Guance sample from an observed, secret-free Evidence Spine projection. */
    public static EvidenceEvaluationSample captured(
            String sampleId,
            String sampleKey,
            String captureIdentityKey,
            int captureRevision,
            String diagnosisId,
            String system,
            String service,
            String scenarioKey,
            GuanceEvidenceSpinePreview preview,
            String modelInputHash,
            Instant evidenceOccurredAt,
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
                captureIdentityKey,
                captureRevision,
                diagnosisId,
                system,
                service,
                scenarioKey,
                SourcePlatform.GUANCE,
                EvidenceSnapshot.from(preview),
                requiredHash(modelInputHash, "modelInputHash"),
                requiredInstant(evidenceOccurredAt, "evidenceOccurredAt"),
                diagnosisFixtureMode,
                ReferenceStatus.EVIDENCE_CAPTURED,
                null,
                null,
                null,
                null,
                0,
                actor,
                null,
                capturedAt,
                null);
    }

    /** Backward-compatible revision-one Guance factory. */
    public static EvidenceEvaluationSample captured(
            String sampleId,
            String sampleKey,
            String diagnosisId,
            String system,
            String service,
            String scenarioKey,
            GuanceEvidenceSpinePreview preview,
            String modelInputHash,
            Instant evidenceOccurredAt,
            boolean diagnosisFixtureMode,
            String actor,
            Instant capturedAt) {
        return captured(
                sampleId,
                sampleKey,
                sampleKey,
                1,
                diagnosisId,
                system,
                service,
                scenarioKey,
                preview,
                modelInputHash,
                evidenceOccurredAt,
                diagnosisFixtureMode,
                actor,
                capturedAt);
    }

    /** Creates a fixture-separated sample from the server-owned recorded Replay lane. */
    public static EvidenceEvaluationSample capturedReplay(
            String sampleId,
            String sampleKey,
            String captureIdentityKey,
            int captureRevision,
            String diagnosisId,
            String system,
            String service,
            String scenarioKey,
            SopSynthesisPreview preview,
            String modelInputHash,
            Instant evidenceOccurredAt,
            boolean diagnosisFixtureMode,
            String actor,
            Instant capturedAt) {
        if (preview == null
                || preview.stage() != SopSynthesisPreview.Stage.READY_FOR_MODEL
                || !preview.fixtureMode()) {
            throw new IllegalArgumentException(
                    "an evaluation sample requires a recorded Replay preview");
        }
        return new EvidenceEvaluationSample(
                sampleId,
                sampleKey,
                captureIdentityKey,
                captureRevision,
                diagnosisId,
                system,
                service,
                scenarioKey,
                SourcePlatform.RECORDED_REPLAY,
                EvidenceSnapshot.from(preview),
                requiredHash(modelInputHash, "modelInputHash"),
                requiredInstant(evidenceOccurredAt, "evidenceOccurredAt"),
                diagnosisFixtureMode,
                ReferenceStatus.EVIDENCE_CAPTURED,
                null,
                null,
                null,
                null,
                0,
                actor,
                null,
                capturedAt,
                null);
    }

    /** Backward-compatible revision-one Replay factory. */
    public static EvidenceEvaluationSample capturedReplay(
            String sampleId,
            String sampleKey,
            String diagnosisId,
            String system,
            String service,
            String scenarioKey,
            SopSynthesisPreview preview,
            String modelInputHash,
            Instant evidenceOccurredAt,
            boolean diagnosisFixtureMode,
            String actor,
            Instant capturedAt) {
        return capturedReplay(
                sampleId,
                sampleKey,
                sampleKey,
                1,
                diagnosisId,
                system,
                service,
                scenarioKey,
                preview,
                modelInputHash,
                evidenceOccurredAt,
                diagnosisFixtureMode,
                actor,
                capturedAt);
    }

    /** Backward-compatible factory for tests and V181 callers without a frozen input hash. */
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
                sampleKey,
                1,
                diagnosisId,
                system,
                service,
                scenarioKey,
                SourcePlatform.GUANCE,
                EvidenceSnapshot.from(preview),
                null,
                null,
                diagnosisFixtureMode,
                ReferenceStatus.EVIDENCE_CAPTURED,
                null,
                null,
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
            ExpectedDisposition expectedDisposition,
            HumanBaseline humanBaseline,
            OutcomeSnapshot authoritativeOutcome,
            String actor,
            Instant finalizedAt) {
        if (referenceStatus != ReferenceStatus.EVIDENCE_CAPTURED) {
            throw new IllegalStateException("evaluation sample reference is already finalized");
        }
        if (reference == null || expectedDisposition == null || authoritativeOutcome == null) {
            throw new IllegalArgumentException(
                    "reference, expected disposition and authoritative outcome are required");
        }
        if (!scenarioKey.equals(reference.scenarioKey())) {
            throw new IllegalArgumentException("reference scenario must match the evaluation sample");
        }
        Instant normalizedFinalizedAt = requiredInstant(finalizedAt, "finalizedAt");
        return new EvidenceEvaluationSample(
                sampleId,
                sampleKey,
                captureIdentityKey,
                captureRevision,
                diagnosisId,
                system,
                service,
                scenarioKey,
                sourcePlatform,
                evidence,
                modelInputHash,
                evidenceOccurredAt,
                diagnosisFixtureMode,
                ReferenceStatus.READY_FOR_EVALUATION,
                reference,
                expectedDisposition,
                null,
                authoritativeOutcome,
                version + 1,
                capturedBy,
                required(actor, "actor"),
                capturedAt,
                normalizedFinalizedAt);
    }

    /** Existing P1 fixtures expect a draft; new T8 callers must submit the oracle explicitly. */
    public EvidenceEvaluationSample finalizeReference(
            ReferenceSolution reference,
            OutcomeSnapshot authoritativeOutcome,
            String actor,
            Instant finalizedAt) {
        return finalizeReference(
                reference,
                ExpectedDisposition.DRAFT,
                null,
                authoritativeOutcome,
                actor,
                finalizedAt);
    }

    public enum SourcePlatform {
        GUANCE,
        RECORDED_REPLAY
    }

    public enum ReferenceStatus {
        EVIDENCE_CAPTURED,
        READY_FOR_EVALUATION
    }

    /**
     * How long the incident actually took a human, before this system existed.
     *
     * <p><b>Why the ledger needs it.</b> The baseline ledger measures machine
     * time — model latency, composed total. The north star measures a person's
     * time: 「从一条不完整报障，到一个带证据、可交接、可复用的定位结论所需的
     * 时间」. Without a human figure on the sample, a shadow cohort can answer
     * "is it right" and cannot answer "does it save anyone anything", which is
     * the whole reason for running one.</p>
     *
     * <p><b>Why the basis is part of the value.</b> A number recalled by the
     * engineer who handled it and a number read out of the ticket system are not
     * the same evidence, and averaging them together would launder the weaker
     * one. They are reported as separate cohorts, for the same reason
     * {@code EXCLUDED} and {@code UNEVALUATED} are never merged.</p>
     */
    public record HumanBaseline(long minutesToLocate, Basis basis, String note) {

        public HumanBaseline {
            if (minutesToLocate <= 0 || minutesToLocate > 60L * 24 * 30) {
                throw new IllegalArgumentException(
                        "human baseline must be a positive number of minutes within 30 days");
            }
            if (basis == null) {
                throw new IllegalArgumentException("human baseline basis is required");
            }
            note = note == null ? "" : note.trim();
            if (note.length() > 500) {
                throw new IllegalArgumentException("human baseline note must be bounded");
            }
        }

        public enum Basis {
            /** Read out of a system of record (ticket timestamps, chat history). */
            MEASURED,
            /** Recalled by the person who handled it. Never reported as measured. */
            ESTIMATED
        }
    }

    /** Human-owned oracle used to score abstention separately from an unhelpful answer. */
    public enum ExpectedDisposition {
        DRAFT,
        ABSTAIN
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

        static EvidenceSnapshot from(SopSynthesisPreview preview) {
            LogTraceSkeleton skeleton = preview.skeleton();
            boolean contrastAvailable = preview.contrastEvidence() != null;
            GuanceEvidenceSpinePreview.Stage stage = contrastAvailable
                    ? GuanceEvidenceSpinePreview.Stage.FULL_SPINE_OBSERVED
                    : GuanceEvidenceSpinePreview.Stage.CORE_CHAIN_OBSERVED;
            return new EvidenceSnapshot(
                    stage,
                    true,
                    preview.matchCount(),
                    preview.psId(),
                    preview.traceEntries(),
                    skeleton.serviceSequence(),
                    skeleton.anomalySequenceIndexes().size(),
                    skeleton.elapsedMs(),
                    ContrastSnapshot.from(skeleton.contrast()),
                    preview.sourceRequestCount(),
                    preview.totalDurationMs(),
                    preview.timings(),
                    List.of(
                            StepSnapshot.replay(
                                    "log_search", preview.searchEvidence(),
                                    REPLAY_EVIDENCE_REFS.get(0)),
                            StepSnapshot.replay(
                                    "log_trace_bundle", preview.traceEvidence(),
                                    REPLAY_EVIDENCE_REFS.get(1)),
                            StepSnapshot.replay(
                                    "contrast_sample", preview.contrastEvidence(),
                                    REPLAY_EVIDENCE_REFS.get(2))),
                    preview.completedAt());
        }
    }

    public record ContrastSnapshot(
            boolean available,
            String discriminatingFeature,
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
                    available && (discriminatingFeature == null || discriminatingFeature.isBlank())
                            ? "legacy_feature_not_recorded"
                            : discriminatingFeature,
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
                    contrast.discriminatingFeature(),
                    contrast.failureSampleCount(),
                    contrast.failureMatchCount(),
                    contrast.successSampleCount(),
                    contrast.successMatchCount(),
                    contrast.failureRate(),
                    contrast.successRate(),
                    contrast.rateDelta());
        }

        static ContrastSnapshot from(LogTraceSkeleton.ContrastSummary contrast) {
            return new ContrastSnapshot(
                    contrast.available(),
                    contrast.discriminatingFeature(),
                    contrast.failureSampleCount(),
                    contrast.failureMatchCount(),
                    contrast.successSampleCount(),
                    contrast.successMatchCount(),
                    contrast.failureRate(),
                    contrast.successRate(),
                    contrast.rateDelta());
        }

        static ContrastSnapshot unavailable() {
            return new ContrastSnapshot(false, null, 0, 0, 0, 0, 0, 0, 0);
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

        static StepSnapshot replay(
                String signalKind,
                SopSynthesisPreview.EvidenceReference reference,
                String missingEvidenceRef) {
            return reference == null
                    ? new StepSnapshot(
                            signalKind,
                            GuanceEvidenceSpinePreview.StepStatus.MISSING,
                            missingEvidenceRef,
                            null)
                    : new StepSnapshot(
                            signalKind,
                            GuanceEvidenceSpinePreview.StepStatus.CANONICAL_RESULT_OBSERVED,
                            reference.queryId(),
                            reference.collectedAt());
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
        List<String> evidenceRefs = GUANCE_EVIDENCE_REFS.getFirst()
                .equals(steps.getFirst().evidenceRef())
                ? GUANCE_EVIDENCE_REFS
                : REPLAY_EVIDENCE_REFS;
        for (int index = 0; index < SIGNAL_KINDS.size(); index++) {
            StepSnapshot step = steps.get(index);
            if (!SIGNAL_KINDS.get(index).equals(step.signalKind())
                    || !evidenceRefs.get(index).equals(step.evidenceRef())) {
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

    private static String requiredHash(String value, String name) {
        String normalized = required(value, name);
        if (!normalized.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256 value");
        }
        return normalized;
    }

    private static String optionalHash(String value, String name) {
        return value == null ? null : requiredHash(value, name);
    }

    private static Instant requiredInstant(Instant value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return value;
    }
}
