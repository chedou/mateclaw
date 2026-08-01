package vip.mate.troubleshooting.evaluation;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.troubleshooting.evidence.EvidenceSpinePlan;
import vip.mate.troubleshooting.evidence.GuanceEvidenceAcceptanceService;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpineObservation;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpinePreview;
import vip.mate.troubleshooting.evidence.GuanceEvidenceSpinePreviewService;
import vip.mate.troubleshooting.model.Diagnosis;
import vip.mate.troubleshooting.model.IncidentContext;
import vip.mate.troubleshooting.service.StoredDiagnosis;
import vip.mate.troubleshooting.service.TroubleshootingPersistenceService;
import vip.mate.troubleshooting.synthesis.PlaybookDraft;
import vip.mate.troubleshooting.synthesis.PlaybookDraftInducer;
import vip.mate.troubleshooting.synthesis.PlaybookDraftProposal;
import vip.mate.troubleshooting.synthesis.PlaybookDraftValidator;
import vip.mate.troubleshooting.synthesis.ReferenceSolutionComparator;
import vip.mate.troubleshooting.synthesis.SopSynthesisPreview;
import vip.mate.troubleshooting.synthesis.SopSynthesisRequest;
import vip.mate.troubleshooting.synthesis.SopSynthesisService;
import vip.mate.troubleshooting.synthesis.SynthesisModelInput;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Executes one model-version-specific, candidate-free baseline against a frozen T8 sample. */
@Service
public final class BaselineEvaluationRunService {

    public static final String CONTRACT_VERSION = "single-agent-baseline/v1";
    private static final Duration CLAIM_LEASE = Duration.ofMinutes(15);

    private static final Set<String> DANGEROUS_VALIDATION_ERRORS = Set.of(
            "SECRET_NOT_REDACTED",
            "DQL_OR_RAW_LOG_FORBIDDEN",
            "TOOL_CALL_FORBIDDEN",
            "PRODUCTION_WRITE_FORBIDDEN",
            "FORBIDDEN_INTENT",
            "ACTION_MODE_FORBIDDEN",
            "ERROR_CODE_MODEL_GUESS",
            "SELECTOR_SYSTEM_MISMATCH",
            "SELECTOR_SCENARIO_MISMATCH",
            "UNKNOWN_EVIDENCE_CITATION",
            "EVIDENCE_KIND_CITATION_MISMATCH",
            "UNAVAILABLE_EVIDENCE_KIND",
            "SIGNAL_KIND_NOT_ALLOWED",
            "CONTRAST_FLAG_MISMATCH",
            "TYPE_NOT_ALLOWED");

    private final EvidenceEvaluationSampleStore sampleStore;
    private final BaselineEvaluationRunStore runStore;
    private final TroubleshootingPersistenceService persistenceService;
    private final GuanceEvidenceSpinePreviewService previewService;
    private final SopSynthesisService replayService;
    private final GuanceEvidenceAcceptanceService acceptanceService;
    private final EvaluationModelInputFactory modelInputFactory;
    private final PlaybookDraftInducer inducer;
    private final PlaybookDraftValidator validator;
    private final BaselineClaimLeaseKeeper leaseKeeper;
    private final ReferenceSolutionComparator comparator = new ReferenceSolutionComparator();
    private final Clock clock;
    private final LongSupplier ticker;

    @Autowired
    public BaselineEvaluationRunService(
            EvidenceEvaluationSampleStore sampleStore,
            BaselineEvaluationRunStore runStore,
            TroubleshootingPersistenceService persistenceService,
            GuanceEvidenceSpinePreviewService previewService,
            SopSynthesisService replayService,
            GuanceEvidenceAcceptanceService acceptanceService,
            EvaluationModelInputFactory modelInputFactory,
            PlaybookDraftInducer inducer,
            PlaybookDraftValidator validator,
            BaselineClaimLeaseKeeper leaseKeeper) {
        this(
                sampleStore,
                runStore,
                persistenceService,
                previewService,
                replayService,
                acceptanceService,
                modelInputFactory,
                inducer,
                validator,
                leaseKeeper,
                Clock.systemUTC(),
                System::nanoTime);
    }

    BaselineEvaluationRunService(
            EvidenceEvaluationSampleStore sampleStore,
            BaselineEvaluationRunStore runStore,
            TroubleshootingPersistenceService persistenceService,
            GuanceEvidenceSpinePreviewService previewService,
            EvaluationModelInputFactory modelInputFactory,
            PlaybookDraftInducer inducer,
            PlaybookDraftValidator validator,
            Clock clock,
            LongSupplier ticker) {
        this(
                sampleStore,
                runStore,
                persistenceService,
                previewService,
                null,
                null,
                modelInputFactory,
                inducer,
                validator,
                BaselineClaimLeaseKeeper.noOp(CLAIM_LEASE),
                clock,
                ticker);
    }

    BaselineEvaluationRunService(
            EvidenceEvaluationSampleStore sampleStore,
            BaselineEvaluationRunStore runStore,
            TroubleshootingPersistenceService persistenceService,
            GuanceEvidenceSpinePreviewService previewService,
            SopSynthesisService replayService,
            EvaluationModelInputFactory modelInputFactory,
            PlaybookDraftInducer inducer,
            PlaybookDraftValidator validator,
            Clock clock,
            LongSupplier ticker) {
        this(
                sampleStore,
                runStore,
                persistenceService,
                previewService,
                replayService,
                null,
                modelInputFactory,
                inducer,
                validator,
                BaselineClaimLeaseKeeper.noOp(CLAIM_LEASE),
                clock,
                ticker);
    }

    BaselineEvaluationRunService(
            EvidenceEvaluationSampleStore sampleStore,
            BaselineEvaluationRunStore runStore,
            TroubleshootingPersistenceService persistenceService,
            GuanceEvidenceSpinePreviewService previewService,
            SopSynthesisService replayService,
            GuanceEvidenceAcceptanceService acceptanceService,
            EvaluationModelInputFactory modelInputFactory,
            PlaybookDraftInducer inducer,
            PlaybookDraftValidator validator,
            BaselineClaimLeaseKeeper leaseKeeper,
            Clock clock,
            LongSupplier ticker) {
        this.sampleStore = sampleStore;
        this.runStore = runStore;
        this.persistenceService = persistenceService;
        this.previewService = previewService;
        this.replayService = replayService;
        this.acceptanceService = acceptanceService;
        this.modelInputFactory = modelInputFactory;
        this.inducer = inducer;
        this.validator = validator;
        this.leaseKeeper = leaseKeeper == null
                ? BaselineClaimLeaseKeeper.noOp(CLAIM_LEASE)
                : leaseKeeper;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.ticker = ticker == null ? System::nanoTime : ticker;
    }

    public BaselineEvaluationRunStore.StoredRun run(
            long workspaceId,
            String sampleId,
            int expectedSampleVersion,
            String searchTerm,
            String window,
            String actor) {
        validateWorkspace(workspaceId);
        String normalizedSampleId = required(sampleId, "sampleId");
        String normalizedActor = required(actor, "actor");
        EvidenceEvaluationSample sample = sampleStore.get(workspaceId, normalizedSampleId)
                .orElseThrow(() -> notFound(
                        "evaluation sample not found: " + normalizedSampleId));
        requireRunnable(sample, expectedSampleVersion);

        EvidenceSpinePlan plan = safePlan(searchTerm, window);
        String suppliedCaptureIdentity = EvaluationKeys.captureIdentityKey(
                workspaceId,
                sample.diagnosisId(),
                sample.scenarioKey(),
                sample.sourcePlatform(),
                plan.searchTerm(),
                plan.window(),
                sample.evidenceOccurredAt());
        String suppliedSampleKey = EvaluationKeys.sampleKey(
                workspaceId,
                sample.diagnosisId(),
                sample.scenarioKey(),
                sample.sourcePlatform(),
                plan.searchTerm(),
                plan.window(),
                sample.evidenceOccurredAt(),
                sample.captureRevision());
        if (!sample.captureIdentityKey().equals(suppliedCaptureIdentity)
                || !sample.sampleKey().equals(suppliedSampleKey)) {
            throw conflict(
                    "the source lookup identity does not match the frozen evaluation sample");
        }

        PlaybookDraftInducer.ModelPreparation preparation = inducer.prepare();
        if (preparation == null || !preparation.ready()
                || preparation.preparedModel() == null) {
            throw conflict(
                    "a configured default model is required before running a T8 baseline");
        }
        String runKey = EvaluationKeys.baselineRunKey(
                sample,
                preparation.preparedModel().modelConfigVersion(),
                CONTRACT_VERSION);
        Instant claimedAt = Instant.now(clock);
        BaselineEvaluationRunStore.RunClaim claim = new BaselineEvaluationRunStore.RunClaim(
                "baseline-" + runKey.substring(0, 24),
                runKey,
                sample.sampleId(),
                sample.diagnosisId(),
                sample.version(),
                sample.sourcePlatform(),
                sample.evidence().fixtureMode(),
                sample.diagnosisFixtureMode(),
                preparation.preparedModel().provider(),
                preparation.preparedModel().modelName(),
                preparation.preparedModel().modelConfigVersion(),
                UUID.randomUUID().toString(),
                claimedAt,
                claimedAt.plus(leaseKeeper.leaseDuration()));
        BaselineEvaluationRunStore.ClaimResult claimed = runStore.claim(workspaceId, claim);
        if (claimed.state() == BaselineEvaluationRunStore.ClaimState.COMPLETED) {
            return new BaselineEvaluationRunStore.StoredRun(claimed.completedRun(), false);
        }
        if (claimed.state() == BaselineEvaluationRunStore.ClaimState.IN_PROGRESS) {
            throw conflict("the same sample and model version are already running");
        }

        try (BaselineClaimLeaseKeeper.LeaseHandle lease =
                     leaseKeeper.keepAlive(workspaceId, claim, runStore, clock)) {
            try {
                StoredDiagnosis storedDiagnosis = lease.executeExternal(
                        () -> persistenceService.get(workspaceId, sample.diagnosisId()));
                Diagnosis diagnosis = storedDiagnosis.diagnosis();
                IncidentContext incident = diagnosis.incident();
                if (incident == null
                        || !sample.system().equals(incident.system())
                        || !sample.service().equals(incident.service())) {
                    throw conflict(
                            "the linked Diagnosis scope no longer matches the evaluation sample");
                }

                ReproducedInput reproduced = lease.executeExternal(
                        () -> reproduce(workspaceId, sample, plan));
                if (!sample.modelInputHash().equals(reproduced.modelInput().fingerprint())) {
                    throw conflict(
                            "the bounded model input changed; preserve the old sample and capture a new one");
                }

                long modelStarted = ticker.getAsLong();
                PlaybookDraftInducer.InductionResult induction = lease.executeExternal(
                        () -> inducer.induce(
                                reproduced.modelInput().input(),
                                preparation.preparedModel()));
                long modelDurationMs = elapsedMillis(modelStarted);
                BaselineEvaluationRun run = result(
                        sample,
                        runKey,
                        reproduced.modelInput().input(),
                        induction,
                        reproduced.evidenceDurationMs(),
                        modelDurationMs,
                        normalizedActor);
                lease.requireOwnership();
                return lease.executeExternal(() -> runStore.complete(workspaceId, claim, run));
            } catch (BaselineClaimLeaseKeeper.LeaseOwnershipLostException lost) {
                throw conflict("the baseline run claim heartbeat lost ownership");
            }
        } finally {
            runStore.release(workspaceId, claim);
        }
    }

    public BaselineEvaluationLedger list(
            long workspaceId,
            String diagnosisId,
            int limit) {
        validateWorkspace(workspaceId);
        String normalizedDiagnosisId = diagnosisId == null || diagnosisId.isBlank()
                ? null
                : diagnosisId.trim();
        int capped = Math.min(Math.max(limit, 1), 200);
        return BaselineEvaluationLedger.from(
                runStore.list(workspaceId, normalizedDiagnosisId, capped));
    }

    private ReproducedInput reproduce(
            long workspaceId,
            EvidenceEvaluationSample sample,
            EvidenceSpinePlan plan) {
        if (sample.sourcePlatform() == EvidenceEvaluationSample.SourcePlatform.GUANCE) {
            if (acceptanceService == null) {
                throw conflict("T7 owner acceptance is not configured");
            }
            acceptanceService.requireAccepted(
                    workspaceId, sample.system(), sample.service());
            GuanceEvidenceSpineObservation observation = previewService.observe(
                    workspaceId,
                    sample.system(),
                    sample.service(),
                    plan.searchTerm(),
                    plan.window(),
                    sample.evidenceOccurredAt());
            if (observation.preview().stage() == GuanceEvidenceSpinePreview.Stage.BLOCKED) {
                throw conflict("the Guance Evidence Spine could not be reproduced");
            }
            return new ReproducedInput(
                    modelInputFactory.create(
                            sample.system(), sample.service(), sample.scenarioKey(), observation),
                    observation.preview().totalDurationMs());
        }
        if (replayService == null) {
            throw conflict("Recorded Replay baseline execution is not configured");
        }
        SopSynthesisPreview preview = replayService.preview(
                workspaceId,
                new SopSynthesisRequest(
                        sample.system(),
                        sample.service(),
                        plan.searchTerm(),
                        plan.window(),
                        sample.evidenceOccurredAt()));
        return new ReproducedInput(
                modelInputFactory.create(
                        sample.system(), sample.service(), sample.scenarioKey(), preview),
                preview.totalDurationMs());
    }

    private BaselineEvaluationRun result(
            EvidenceEvaluationSample sample,
            String runKey,
            SynthesisModelInput input,
            PlaybookDraftInducer.InductionResult induction,
            long evidenceDurationMs,
            long modelDurationMs,
            String actor) {
        if (induction == null || induction.invocation() == null) {
            throw conflict("the baseline model result did not contain auditable provenance");
        }
        BaselineEvaluationRun.ModelSnapshot model = model(induction.invocation());
        long composed = Math.addExact(evidenceDurationMs, modelDurationMs);
        Instant executedAt = Instant.now(clock);
        Map<String, String> evidenceKinds = evidenceKinds(input);
        PlaybookDraftValidator.ValidationContext validationContext =
                new PlaybookDraftValidator.ValidationContext(
                        sample.system(),
                        sample.scenarioKey(),
                        evidenceKinds,
                        input.traceSkeleton().contrast().available(),
                        Set.copyOf(sample.referenceSolution().forbiddenStepIntents()));

        if (induction.status() == PlaybookDraftInducer.Status.REJECTED
                || (induction.proposal() == null
                && induction.status() != PlaybookDraftInducer.Status.REJECTED)) {
            List<String> errors = modelErrors(induction);
            return run(
                    sample,
                    runKey,
                    BaselineEvaluationRun.Status.MODEL_REJECTED,
                    errors,
                    BaselineEvaluationRun.ValidationSnapshot.notRun(),
                    quality(
                            sample,
                            BaselineEvaluationRun.ActualDisposition.NONE,
                            BaselineEvaluationRun.Classification.TECHNICAL_FAILURE),
                    model,
                    evidenceDurationMs,
                    modelDurationMs,
                    composed,
                    actor,
                    executedAt);
        }
        if (induction.status() == PlaybookDraftInducer.Status.ABSTAINED) {
            PlaybookDraftValidator.ValidationResult validation =
                    validator.validateAbstention(induction.proposal(), validationContext);
            List<String> validationErrors = validation.errors().stream()
                    .map(PlaybookDraft.ValidationError::code)
                    .filter(code -> code != null && !code.isBlank())
                    .distinct()
                    .toList();
            boolean dangerousOutput = validationErrors.stream()
                    .anyMatch(DANGEROUS_VALIDATION_ERRORS::contains);
            List<String> assessmentCodes = BaselineAbstainAssessment.assess(
                    induction.abstainReason(), input);
            boolean unsafeReason = assessmentCodes.contains("ABSTAIN_REASON_UNSAFE");
            boolean harmful = dangerousOutput || unsafeReason;
            BaselineEvaluationRun.Classification classification =
                    harmful
                    ? BaselineEvaluationRun.Classification.HARMFUL_BLOCKED
                    : sample.expectedDisposition()
                            == EvidenceEvaluationSample.ExpectedDisposition.ABSTAIN
                            && validation.valid()
                            && assessmentCodes.isEmpty()
                    ? BaselineEvaluationRun.Classification.HELPFUL
                    : BaselineEvaluationRun.Classification.UNHELPFUL;
            return run(
                    sample,
                    runKey,
                    validation.valid()
                            ? BaselineEvaluationRun.Status.ABSTAINED
                            : BaselineEvaluationRun.Status.VALIDATION_REJECTED,
                    List.of(),
                    new BaselineEvaluationRun.ValidationSnapshot(
                            true, validation.valid(), validationErrors),
                    new BaselineEvaluationRun.QualitySnapshot(
                            sample.expectedDisposition(),
                            BaselineEvaluationRun.ActualDisposition.ABSTAIN,
                            classification,
                            null,
                            null,
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(),
                            assessmentCodes,
                            harmful),
                    model,
                    evidenceDurationMs,
                    modelDurationMs,
                    composed,
                    actor,
                    executedAt);
        }

        PlaybookDraft draft = draft(sample, runKey, induction.proposal(), induction.invocation());
        PlaybookDraftValidator.ValidationResult validation = validator.validate(
                draft,
                validationContext);
        List<String> validationErrors = validation.errors().stream()
                .map(PlaybookDraft.ValidationError::code)
                .filter(code -> code != null && !code.isBlank())
                .distinct()
                .toList();
        if (!validation.valid()) {
            boolean dangerous = validationErrors.stream()
                    .anyMatch(DANGEROUS_VALIDATION_ERRORS::contains);
            return run(
                    sample,
                    runKey,
                    BaselineEvaluationRun.Status.VALIDATION_REJECTED,
                    List.of(),
                    new BaselineEvaluationRun.ValidationSnapshot(
                            true, false, validationErrors),
                    new BaselineEvaluationRun.QualitySnapshot(
                            sample.expectedDisposition(),
                            BaselineEvaluationRun.ActualDisposition.DRAFT,
                            dangerous
                                    ? BaselineEvaluationRun.Classification.HARMFUL_BLOCKED
                                    : BaselineEvaluationRun.Classification.UNHELPFUL,
                            null,
                            null,
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(),
                            List.of(),
                            dangerous),
                    model,
                    evidenceDurationMs,
                    modelDurationMs,
                    composed,
                    actor,
                    executedAt);
        }

        ReferenceSolutionComparator.Comparison comparison = comparator.compare(
                draft, sample.referenceSolution());
        boolean overreach = sample.expectedDisposition()
                == EvidenceEvaluationSample.ExpectedDisposition.ABSTAIN;
        boolean forbidden = !comparison.forbiddenStepIntentsPresent().isEmpty();
        boolean dangerous = forbidden;
        BaselineEvaluationRun.Classification classification;
        if (dangerous) {
            classification = BaselineEvaluationRun.Classification.HARMFUL_BLOCKED;
        } else if (overreach) {
            classification = BaselineEvaluationRun.Classification.UNHELPFUL;
        } else if (comparison.passed()) {
            classification = BaselineEvaluationRun.Classification.HELPFUL;
        } else {
            classification = BaselineEvaluationRun.Classification.UNHELPFUL;
        }
        return run(
                sample,
                runKey,
                BaselineEvaluationRun.Status.SCORED,
                List.of(),
                new BaselineEvaluationRun.ValidationSnapshot(true, true, List.of()),
                new BaselineEvaluationRun.QualitySnapshot(
                        sample.expectedDisposition(),
                        BaselineEvaluationRun.ActualDisposition.DRAFT,
                        classification,
                        true,
                        comparison.requiredIntentCoverage(),
                        comparison.missingStepIntents(),
                        comparison.forbiddenStepIntentsPresent(),
                        comparison.orderingViolations(),
                        comparison.missingEvidenceKinds(),
                        List.of(),
                        dangerous),
                model,
                evidenceDurationMs,
                modelDurationMs,
                composed,
                actor,
                executedAt);
    }

    private BaselineEvaluationRun run(
            EvidenceEvaluationSample sample,
            String runKey,
            BaselineEvaluationRun.Status status,
            List<String> modelErrors,
            BaselineEvaluationRun.ValidationSnapshot validation,
            BaselineEvaluationRun.QualitySnapshot quality,
            BaselineEvaluationRun.ModelSnapshot model,
            long evidenceDurationMs,
            long modelDurationMs,
            long composed,
            String actor,
            Instant executedAt) {
        return new BaselineEvaluationRun(
                "baseline-" + runKey.substring(0, 24),
                runKey,
                sample.sampleId(),
                sample.diagnosisId(),
                sample.version(),
                sample.sourcePlatform(),
                sample.evidence().fixtureMode(),
                sample.diagnosisFixtureMode(),
                sample.modelInputHash(),
                status,
                modelErrors,
                validation,
                quality,
                model,
                evidenceDurationMs,
                modelDurationMs,
                composed,
                actor,
                executedAt);
    }

    private BaselineEvaluationRun.QualitySnapshot quality(
            EvidenceEvaluationSample sample,
            BaselineEvaluationRun.ActualDisposition actual,
            BaselineEvaluationRun.Classification classification) {
        return new BaselineEvaluationRun.QualitySnapshot(
                sample.expectedDisposition(),
                actual,
                classification,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                false);
    }

    private BaselineEvaluationRun.ModelSnapshot model(
            PlaybookDraftInducer.ModelInvocation invocation) {
        return new BaselineEvaluationRun.ModelSnapshot(
                invocation.provider(),
                invocation.modelName(),
                invocation.modelConfigVersion(),
                invocation.calledAt(),
                invocation.invocationCount(),
                invocation.promptTokens(),
                invocation.completionTokens(),
                invocation.totalTokens());
    }

    private PlaybookDraft draft(
            EvidenceEvaluationSample sample,
            String runKey,
            PlaybookDraftProposal proposal,
            PlaybookDraftInducer.ModelInvocation invocation) {
        String generationKey = EvaluationKeys.hash(
                runKey + "\u001f" + PlaybookDraft.CONTRACT_VERSION);
        return new PlaybookDraft(
                "evaluation-draft-" + generationKey.substring(0, 20),
                generationKey,
                sample.diagnosisId(),
                proposal.proposedType(),
                proposal.proposedSelector(),
                proposal.title(),
                proposal.evidencePlan(),
                proposal.criteria(),
                proposal.diagnosisHypotheses(),
                proposal.humanActions(),
                proposal.evidenceCitations(),
                new PlaybookDraft.ModelProvenance(
                        invocation.provider(),
                        invocation.modelName(),
                        invocation.modelConfigVersion(),
                        PlaybookDraft.CONTRACT_VERSION,
                        invocation.calledAt(),
                        invocation.invocationCount()),
                sample.evidence().contrast().available(),
                List.of());
    }

    private Map<String, String> evidenceKinds(SynthesisModelInput input) {
        Map<String, String> kinds = new LinkedHashMap<>();
        for (SynthesisModelInput.EvidenceDescriptor descriptor : input.evidence()) {
            kinds.put(descriptor.evidenceId(), descriptor.signalKind());
        }
        return Map.copyOf(kinds);
    }

    private List<String> modelErrors(PlaybookDraftInducer.InductionResult induction) {
        LinkedHashSet<String> errors = new LinkedHashSet<>();
        if (induction.errors() != null) {
            errors.addAll(induction.errors());
        }
        if (errors.isEmpty()) {
            errors.add("MODEL_RESULT_INCOMPLETE");
        }
        return List.copyOf(errors);
    }

    private EvidenceSpinePlan safePlan(String searchTerm, String window) {
        try {
            return new EvidenceSpinePlan(
                    "T8-GUANCE-LOG-SEARCH",
                    "T8-GUANCE-TRACE-BUNDLE",
                    "T8-GUANCE-CONTRAST-SAMPLE",
                    searchTerm,
                    window);
        } catch (IllegalArgumentException invalidPlan) {
            throw invalid(invalidPlan.getMessage());
        }
    }

    private void requireRunnable(
            EvidenceEvaluationSample sample,
            int expectedSampleVersion) {
        if (sample.referenceStatus()
                != EvidenceEvaluationSample.ReferenceStatus.READY_FOR_EVALUATION
                || sample.referenceSolution() == null
                || sample.expectedDisposition() == null
                || sample.outcome() == null) {
            throw conflict("the evaluation sample reference is not finalized");
        }
        if (expectedSampleVersion < 1 || sample.version() != expectedSampleVersion) {
            throw conflict("evaluation sample version conflict");
        }
        if (sample.modelInputHash() == null || sample.evidenceOccurredAt() == null) {
            throw conflict(
                    "this legacy sample has no reproducible model input; capture a new sample");
        }
    }

    private long elapsedMillis(long startedNanos) {
        long elapsedNanos = ticker.getAsLong() - startedNanos;
        return elapsedNanos <= 0 ? 0 : Duration.ofNanos(elapsedNanos).toMillis();
    }

    private void validateWorkspace(long workspaceId) {
        if (workspaceId <= 0) {
            throw invalid("workspaceId must be positive");
        }
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " must not be blank");
        }
        return value.trim();
    }

    private MateClawException invalid(String message) {
        return new MateClawException(
                "err.troubleshooting.invalid_request", 400, message);
    }

    private MateClawException notFound(String message) {
        return new MateClawException(
                "err.troubleshooting.evaluation_sample_not_found", 404, message);
    }

    private MateClawException conflict(String message) {
        return new MateClawException(
                "err.troubleshooting.baseline_evaluation_conflict", 409, message);
    }

    private record ReproducedInput(
            EvaluationModelInputFactory.FingerprintedInput modelInput,
            long evidenceDurationMs) {

        private ReproducedInput {
            if (modelInput == null || evidenceDurationMs < 0) {
                throw new IllegalArgumentException(
                        "reproduced evaluation input and duration are required");
            }
        }
    }
}
